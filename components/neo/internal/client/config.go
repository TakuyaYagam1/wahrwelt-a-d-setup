package client

import (
	"github.com/sirupsen/logrus"
	"github.com/spf13/viper"
)

func MustUnmarshalConfig() *Config {
	cfg := new(Config)
	if err := viper.Unmarshal(&cfg); err != nil {
		logrus.Fatalf("Не удалось разобрать структуру конфигурации: %v", err)
	}
	return cfg
}

type Config struct {
	Host                   string `mapstructure:"host"`
	MetricsHost            string `mapstructure:"metrics_host"`
	ExploitDir             string `mapstructure:"exploit_dir"`
	GrpcAuthKey            string `mapstructure:"grpc_auth_key"`
	UseTLS                 bool   `mapstructure:"use_tls"`
	AllowBroadcastCommands bool   `mapstructure:"allow_broadcast_commands"`
}
