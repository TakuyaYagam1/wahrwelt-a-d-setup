package config

import (
	"encoding/json"
	"fmt"
	"io"
	"net/url"
	"strings"
	"time"

	"github.com/c4t-but-s4d/neo/v2/pkg/grpcauth"
)

type Config struct {
	Debug            bool              `mapstructure:"debug"`
	Addr             string            `mapstructure:"addr"`
	DBPath           string            `mapstructure:"db_path"`
	RedisURL         string            `mapstructure:"redis_url"`
	BaseDir          string            `mapstructure:"base_dir"`
	PingEvery        time.Duration     `mapstructure:"ping_every"`
	SubmitEvery      time.Duration     `mapstructure:"submit_every"`
	FarmConfig       FarmConfig        `mapstructure:"farm"`
	GrpcAuthKey      string            `mapstructure:"grpc_auth_key"`
	WorkerKey        string            `mapstructure:"worker_key"`
	Environ          map[string]string `mapstructure:"env"`
	MetricsNamespace string            `mapstructure:"metrics_namespace"`
}

type FarmConfig struct {
	URL        string            `mapstructure:"url"`
	Password   string            `mapstructure:"password"`
	FlagRegexp string            `json:"FLAG_FORMAT"`
	Teams      map[string]string `json:"TEAMS"`
}

func (cfg *Config) Validate() error {
	if err := grpcauth.ValidateKeyPair(cfg.GrpcAuthKey, cfg.WorkerKey); err != nil {
		return err
	}
	if strings.TrimSpace(cfg.FarmConfig.Password) == "" {
		return fmt.Errorf("farm.password обязателен")
	}
	u, err := url.Parse(cfg.FarmConfig.URL)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil {
		return fmt.Errorf("farm.url должен быть HTTP(S) адресом без учетных данных")
	}
	if cfg.Addr == "" || cfg.DBPath == "" || cfg.BaseDir == "" || cfg.RedisURL == "" {
		return fmt.Errorf("обязательны поля addr, db_path, base_dir и redis_url")
	}
	if cfg.PingEvery <= 0 || cfg.SubmitEvery <= 0 {
		return fmt.Errorf("ping_every и submit_every должны быть положительными")
	}
	return nil
}

func (cfg *FarmConfig) ParseJSON(r io.Reader) error {
	dec := json.NewDecoder(r)
	if err := dec.Decode(cfg); err != nil {
		return fmt.Errorf("разбор JSON: %w", err)
	}
	var extra any
	if err := dec.Decode(&extra); err != io.EOF {
		if err == nil {
			return fmt.Errorf("после объекта JSON обнаружены лишние данные")
		}
		return fmt.Errorf("проверка конца JSON: %w", err)
	}
	return nil
}
