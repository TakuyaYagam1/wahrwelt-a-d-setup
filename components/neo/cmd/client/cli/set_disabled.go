package cli

import (
	"context"
	"fmt"

	"github.com/spf13/cobra"

	"github.com/c4t-but-s4d/neo/v2/internal/client"
)

type setDisabledCli struct {
	*baseCLI
	exploitID string
	disabled  bool
}

func NewSetDisabled(_ *cobra.Command, args []string, cfg *client.Config, disabled bool) NeoCLI {
	return &setDisabledCli{
		baseCLI:   &baseCLI{cfg: cfg},
		exploitID: args[0],
		disabled:  disabled,
	}
}

func (sc *setDisabledCli) Run(ctx context.Context) error {
	c, err := sc.client()
	if err != nil {
		return fmt.Errorf("не удалось создать клиент: %w", err)
	}
	state, err := c.GetServerState(ctx)
	if err != nil {
		return fmt.Errorf("не удалось получить конфигурацию сервера: %w", err)
	}

	if spl := getExploitFromState(state, sc.exploitID); spl == nil {
		return fmt.Errorf("эксплойт %s не найден", sc.exploitID)
	} else if err := c.SetExploitDisabled(ctx, spl.ExploitId, sc.disabled); err != nil {
		return fmt.Errorf("не удалось изменить состояние эксплойта: %w", err)
	}

	return nil
}
