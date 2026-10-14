package config

import (
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func validTestConfig() *Config {
	return &Config{
		Addr:        ":5005",
		DBPath:      "/data/db.db",
		RedisURL:    "redis://neo-redis:6379/0",
		BaseDir:     "/data/exploits",
		PingEvery:   time.Second,
		SubmitEvery: time.Second,
		GrpcAuthKey: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
		WorkerKey:   "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
		FarmConfig: FarmConfig{
			URL:      "http://farm:5000",
			Password: "farm-secret",
		},
	}
}

func TestValidateRequiresDifferentAdminAndWorkerKeys(t *testing.T) {
	cfg := validTestConfig()
	require.NoError(t, cfg.Validate())

	cfg.WorkerKey = cfg.GrpcAuthKey
	require.ErrorContains(t, cfg.Validate(), "должны различаться")
}

func TestValidateRequiresFarmCredentialsAndHTTPURL(t *testing.T) {
	cfg := validTestConfig()
	cfg.FarmConfig.Password = ""
	require.ErrorContains(t, cfg.Validate(), "farm.password")

	cfg = validTestConfig()
	cfg.FarmConfig.URL = "http://user:pass@farm:5000"
	require.ErrorContains(t, cfg.Validate(), "без учетных данных")
}
