package cmd

import (
	"context"
	"fmt"
	"os"
	"strings"

	"github.com/sirupsen/logrus"
	"github.com/spf13/cobra"
	"github.com/spf13/viper"

	"github.com/c4t-but-s4d/neo/v2/internal/logger"
)

// rootCmd описывает основную команду клиента.
var rootCmd = &cobra.Command{
	Use:   "client",
	Short: "Клиент Neo",
}

// Execute запускает выбранную команду клиента.
func Execute(ctx context.Context) {
	if err := rootCmd.ExecuteContext(ctx); err != nil {
		fmt.Println(err)
		os.Exit(1)
	}
}

func init() {
	cobra.OnInitialize(initConfig)
	rootCmd.PersistentFlags().StringP("config", "c", "client_config.yml", "путь к файлу конфигурации")
	rootCmd.PersistentFlags().BoolP("verbose", "v", false, "включить подробные журналы")
	rootCmd.PersistentFlags().String("host", "127.0.0.1:5005", "адрес сервера")
	rootCmd.PersistentFlags().String("metrics_host", "", "адрес Pushgateway для метрик")

	mustBindPersistent(rootCmd, "config")
	mustBindPersistent(rootCmd, "host")
	mustBindPersistent(rootCmd, "verbose")
	mustBindPersistent(rootCmd, "metrics_host")
}

func mustBindPersistent(c *cobra.Command, flag string) {
	if err := viper.BindPFlag(flag, c.PersistentFlags().Lookup(flag)); err != nil {
		logrus.Fatalf("Не удалось связать параметр %s: %v", flag, err)
	}
}

// initConfig загружает файл конфигурации и переменные окружения.
func initConfig() {
	logger.Init()

	viper.SetConfigFile(viper.GetString("config"))
	viper.SetConfigType("yaml")

	viper.SetEnvPrefix("NEO")
	viper.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
	viper.AutomaticEnv() // считывать совпадающие переменные окружения
	viper.SetDefault("allow_broadcast_commands", false)

	// Если файл конфигурации найден, загрузить его.
	if err := viper.ReadInConfig(); err == nil {
		logrus.Info("Используется файл конфигурации:", viper.ConfigFileUsed())
	}

	if viper.GetBool("verbose") {
		logrus.SetLevel(logrus.DebugLevel)
	} else {
		logrus.SetLevel(logrus.InfoLevel)
	}

}
