package cli

import (
	"regexp"
	"testing"
	"time"

	"github.com/c4t-but-s4d/neo/v2/internal/config"
	epb "github.com/c4t-but-s4d/neo/v2/proto/go/exploits"
	"github.com/stretchr/testify/require"
	"google.golang.org/protobuf/types/known/durationpb"
)

func TestFormatServerInfoOmitsSecrets(t *testing.T) {
	const secret = "farm-password-must-not-appear"
	cfg := &config.ExploitsConfig{
		FarmURL:      "http://farm:5000",
		FarmPassword: secret,
		FlagRegexp:   regexp.MustCompile(`FLAG\\{.*\\}`),
		PingEvery:    time.Second * 5,
		SubmitEvery:  time.Second * 2,
		Environ:      []string{"TOKEN=environment-secret"},
	}
	state := &epb.ServerState{
		Config: &epb.Config{
			FarmPassword: secret,
			Environ:      []string{"TOKEN=environment-secret"},
		},
		ClientTeamMap: map[string]*epb.TeamBucket{
			"worker": {Teams: map[string]string{"team-1": "127.0.0.1"}},
		},
		Exploits: []*epb.ExploitState{{
			ExploitId: "check",
			Version:   1,
			Config: &epb.ExploitConfiguration{
				Entrypoint: "check.py",
				RunEvery:   durationpb.New(time.Second * 30),
				Timeout:    durationpb.New(time.Second * 10),
			},
		}},
	}

	output := formatServerInfo(cfg, state)
	require.NotContains(t, output, secret)
	require.NotContains(t, output, "environment-secret")
	require.Contains(t, output, "http://farm:5000")
	require.Contains(t, output, "team-1: 127.0.0.1")
	require.Contains(t, output, "check, версия 1")
}
