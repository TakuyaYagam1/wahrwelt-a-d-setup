package cli

import (
	"context"
	"fmt"
	"sort"
	"strings"

	"github.com/spf13/cobra"

	"github.com/c4t-but-s4d/neo/v2/internal/client"
	"github.com/c4t-but-s4d/neo/v2/internal/config"
	epb "github.com/c4t-but-s4d/neo/v2/proto/go/exploits"
)

type infoCLI struct {
	*baseCLI
}

func NewInfo(_ *cobra.Command, _ []string, cfg *client.Config) NeoCLI {
	return &infoCLI{&baseCLI{cfg: cfg}}
}

func (ic *infoCLI) Run(ctx context.Context) error {
	c, err := ic.client()
	if err != nil {
		return err
	}
	state, err := c.GetServerState(ctx)
	if err != nil {
		return fmt.Errorf("не удалось запросить конфигурацию сервера: %w", err)
	}
	cfg, err := config.FromProto(state.Config)
	if err != nil {
		return fmt.Errorf("не удалось разобрать конфигурацию: %w", err)
	}
	fmt.Print(formatServerInfo(cfg, state))
	return nil
}

func formatServerInfo(cfg *config.ExploitsConfig, state *epb.ServerState) string {
	var out strings.Builder
	fmt.Fprintf(&out, "Farm: %s\n", cfg.FarmURL)
	fmt.Fprintf(&out, "Интервал опроса: %s\n", cfg.PingEvery)
	fmt.Fprintf(&out, "Интервал отправки флагов: %s\n", cfg.SubmitEvery)
	fmt.Fprintf(&out, "Правило флага: %s\n", cfg.FlagRegexp)
	fmt.Fprintf(&out, "Переменных окружения: %d\n", len(cfg.Environ))

	fmt.Fprintln(&out, "Адреса команд:")
	bucketIDs := make([]string, 0, len(state.ClientTeamMap))
	for id := range state.ClientTeamMap {
		bucketIDs = append(bucketIDs, id)
	}
	sort.Strings(bucketIDs)
	for _, bucketID := range bucketIDs {
		bucket := state.ClientTeamMap[bucketID]
		teamIDs := make([]string, 0, len(bucket.GetTeams()))
		for id := range bucket.GetTeams() {
			teamIDs = append(teamIDs, id)
		}
		sort.Strings(teamIDs)
		for _, teamID := range teamIDs {
			fmt.Fprintf(&out, "  %s: %s\n", teamID, bucket.GetTeams()[teamID])
		}
	}

	fmt.Fprintln(&out, "Эксплойты:")
	for _, exploit := range state.Exploits {
		if exploit == nil {
			continue
		}
		cfg := exploit.GetConfig()
		fmt.Fprintf(&out, "  %s, версия %d", exploit.GetExploitId(), exploit.GetVersion())
		if cfg != nil {
			fmt.Fprintf(&out, ", интервал %s, тайм-аут %s, бесконечный режим %t, отключен %t, точка входа %s",
				cfg.GetRunEvery().AsDuration(), cfg.GetTimeout().AsDuration(), cfg.GetEndless(), cfg.GetDisabled(), cfg.GetEntrypoint())
		}
		fmt.Fprintln(&out)
	}
	return out.String()
}
