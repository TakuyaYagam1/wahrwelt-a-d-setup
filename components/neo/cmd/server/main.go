package main

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"os/signal"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/sirupsen/logrus"
	"github.com/spf13/pflag"
	"github.com/spf13/viper"
	"google.golang.org/grpc"
	_ "google.golang.org/grpc/encoding/gzip"

	"github.com/c4t-but-s4d/neo/v2/internal/logger"
	"github.com/c4t-but-s4d/neo/v2/internal/server/config"
	"github.com/c4t-but-s4d/neo/v2/internal/server/exploits"
	"github.com/c4t-but-s4d/neo/v2/internal/server/fs"
	logs "github.com/c4t-but-s4d/neo/v2/internal/server/logs"
	"github.com/c4t-but-s4d/neo/v2/pkg/grpcauth"
	"github.com/c4t-but-s4d/neo/v2/pkg/neosync"
	epb "github.com/c4t-but-s4d/neo/v2/proto/go/exploits"
	fspb "github.com/c4t-but-s4d/neo/v2/proto/go/fileserver"
	logspb "github.com/c4t-but-s4d/neo/v2/proto/go/logs"
)

func main() {
	logger.Init()
	if err := setupConfig(); err != nil {
		logrus.Fatalf("Не удалось подготовить конфигурацию: %v", err)
	}

	cfg, err := readConfig()
	if err != nil {
		logrus.Fatalf("Не удалось прочитать конфигурацию: %v", err)
	}

	setLogLevel(cfg)

	initCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	fc := exploits.NewFarmClient(cfg.FarmConfig)
	if err := fc.FillConfig(initCtx, &cfg.FarmConfig); err != nil {
		logrus.Fatalf("Не удалось получить конфигурацию Farm: %v", err)
	}
	if strings.TrimSpace(cfg.FarmConfig.FlagRegexp) == "" {
		logrus.Fatal("В конфигурации Farm не задан FLAG_FORMAT")
	}
	if _, err := regexp.Compile(cfg.FarmConfig.FlagRegexp); err != nil {
		logrus.Fatalf("В конфигурации Farm неверный FLAG_FORMAT: %v", err)
	}

	st, err := exploits.NewBoltStorage(cfg.DBPath)
	if err != nil {
		logrus.Fatalf("Не удалось создать хранилище Bolt: %v", err)
	}

	logStore, err := logs.NewLogStorage(initCtx, cfg.RedisURL)
	if err != nil {
		logrus.Fatalf("Не удалось создать хранилище журналов: %v", err)
	}

	exploitsServer := exploits.New(cfg, st)
	fsServer, err := fs.New(cfg)
	if err != nil {
		logrus.Fatalf("Не удалось создать файловый сервер: %v", err)
	}
	logsServer := logs.New(logStore)

	lis, err := net.Listen("tcp", cfg.Addr)
	if err != nil {
		logrus.Fatalf("Не удалось открыть сетевой порт: %v", err)
	}

	authInterceptor := grpcauth.NewServerInterceptorWithWorkerKey(cfg.GrpcAuthKey, cfg.WorkerKey)
	opts := []grpc.ServerOption{
		grpc.UnaryInterceptor(authInterceptor.Unary()),
		grpc.StreamInterceptor(authInterceptor.Stream()),
		grpc.MaxRecvMsgSize(4 * 1024 * 1024),
		grpc.MaxSendMsgSize(4 * 1024 * 1024),
	}

	s := grpc.NewServer(opts...)
	epb.RegisterServiceServer(s, exploitsServer)
	fspb.RegisterServiceServer(s, fsServer)
	logspb.RegisterServiceServer(s, logsServer)
	metricsMux := http.NewServeMux()
	metricsMux.Handle("/metrics", promhttp.Handler())
	metricsServer := &http.Server{
		Addr:              viper.GetString("metrics.address"),
		Handler:           metricsMux,
		ReadHeaderTimeout: 5 * time.Second,
	}

	runCtx, cancel := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer cancel()

	wg := sync.WaitGroup{}

	wg.Add(4)
	go func() {
		defer wg.Done()
		logrus.Infof("Запуск метрик на %s", metricsServer.Addr)
		if err := metricsServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logrus.Errorf("Ошибка сервера метрик: %v", err)
			cancel()
		}
	}()
	go func() {
		defer wg.Done()
		exploitsServer.HeartBeat(runCtx)
	}()
	go func() {
		defer wg.Done()
		exploitsServer.UpdateMetrics(runCtx)
	}()
	go func() {
		defer wg.Done()
		<-runCtx.Done()
		logrus.Info("Получен сигнал остановки, сервер завершает работу")
		s.GracefulStop()
		shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer shutdownCancel()
		if err := metricsServer.Shutdown(shutdownCtx); err != nil {
			logrus.Errorf("Ошибка остановки сервера метрик: %v", err)
		}
	}()

	logrus.Infof("Запуск сервера на %s", cfg.Addr)
	if err := s.Serve(lis); err != nil {
		logrus.Fatalf("Ошибка сервера: %v", err)
	}

	select {
	case <-neosync.AwaitWG(&wg):
		logrus.Info("Остановка завершена")
	case <-time.After(10 * time.Second):
		logrus.Warn("Истекло время ожидания остановки")
	}
}

func setupConfig() error {
	pflag.BoolP("debug", "v", false, "Enable verbose logging")
	pflag.StringP("config", "c", "server_config.yml", "Path to config file")
	pflag.Parse()

	if err := viper.BindPFlags(pflag.CommandLine); err != nil {
		return fmt.Errorf("связь параметров командной строки: %w", err)
	}

	viper.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
	viper.SetEnvPrefix("NEO")
	viper.AutomaticEnv()

	viper.MustBindEnv("grpc_auth_key")
	viper.MustBindEnv("worker_key", "NEO_WORKER_KEY")
	viper.MustBindEnv("farm.password")
	viper.MustBindEnv("farm.url")

	viper.SetDefault("config", "server_config.yml")
	viper.SetDefault("ping_every", time.Second*5)
	viper.SetDefault("submit_every", time.Second*2)
	viper.SetDefault("metrics.address", "127.0.0.1:3000")
	viper.SetDefault("addr", ":5005")

	return nil
}

func readConfig() (*config.Config, error) {
	viper.SetConfigFile(viper.GetString("config"))
	viper.SetConfigType("yaml")

	if err := viper.ReadInConfig(); err != nil {
		return nil, fmt.Errorf("чтение конфигурации YAML: %w", err)
	}

	cfg := &config.Config{}
	if err := viper.Unmarshal(cfg); err != nil {
		return nil, fmt.Errorf("разбор конфигурации: %w", err)
	}
	if err := cfg.Validate(); err != nil {
		return nil, fmt.Errorf("неверная конфигурация: %w", err)
	}

	return cfg, nil
}

func setLogLevel(cfg *config.Config) {
	if cfg.Debug {
		logrus.SetLevel(logrus.DebugLevel)
	} else {
		logrus.SetLevel(logrus.InfoLevel)
	}
}
