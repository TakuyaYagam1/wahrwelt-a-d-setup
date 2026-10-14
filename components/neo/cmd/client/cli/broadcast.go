package cli

import (
	"context"
	"fmt"

	"github.com/sirupsen/logrus"
	"github.com/spf13/cobra"

	"github.com/c4t-but-s4d/neo/v2/internal/client"
)

type broadcastCLI struct {
	*baseCLI
	cmd string
}

func NewBroadcast(cmd *cobra.Command, _ []string, cfg *client.Config) NeoCLI {
	command, err := cmd.Flags().GetString("command")
	if err != nil {
		logrus.Fatalf("Не удалось прочитать команду: %v", err)
	}
	return &broadcastCLI{
		baseCLI: &baseCLI{cfg: cfg},
		cmd:     command,
	}
}

func (bc *broadcastCLI) Run(ctx context.Context) error {
	logrus.Info("Отправка команды всем подключенным клиентам")
	c, err := bc.client()
	if err != nil {
		return fmt.Errorf("не удалось создать клиент: %w", err)
	}
	if err := c.BroadcastCommand(ctx, bc.cmd); err != nil {
		return fmt.Errorf("не удалось отправить широковещательную команду: %w", err)
	}
	return nil
}
