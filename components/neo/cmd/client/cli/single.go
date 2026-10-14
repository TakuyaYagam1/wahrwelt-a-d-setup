package cli

import (
	"context"
	"fmt"

	"github.com/sirupsen/logrus"
	"github.com/spf13/cobra"

	"github.com/c4t-but-s4d/neo/v2/internal/client"
)

type singleRunCLI struct {
	*baseCLI
	exploitID string
}

func NewSingleRun(_ *cobra.Command, args []string, cfg *client.Config) NeoCLI {
	return &singleRunCLI{
		baseCLI:   &baseCLI{cfg: cfg},
		exploitID: args[0],
	}
}

func (sc *singleRunCLI) Run(ctx context.Context) error {
	c, err := sc.client()
	if err != nil {
		return fmt.Errorf("не удалось создать клиент: %w", err)
	}
	state, err := c.GetServerState(ctx)
	if err != nil {
		return fmt.Errorf("не удалось получить конфигурацию сервера: %w", err)
	}

	if getExploitFromState(state, sc.exploitID) == nil {
		logrus.Fatalf("Эксплойт %s не найден. Сначала добавьте его.", sc.exploitID)
	}
	if err := c.SingleRun(ctx, sc.exploitID); err != nil {
		return fmt.Errorf("не удалось выполнить разовый запуск: %w", err)
	}

	return nil
}
