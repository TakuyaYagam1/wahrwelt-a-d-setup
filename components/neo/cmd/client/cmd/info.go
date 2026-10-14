package cmd

import (
	"github.com/sirupsen/logrus"
	"github.com/spf13/cobra"

	"github.com/c4t-but-s4d/neo/v2/cmd/client/cli"
	"github.com/c4t-but-s4d/neo/v2/internal/client"
)

// infoCmd описывает команду просмотра текущего состояния.
var infoCmd = &cobra.Command{
	Use:   "info",
	Short: "Показать текущее состояние",
	Run: func(cmd *cobra.Command, args []string) {
		cfg := client.MustUnmarshalConfig()
		cli := cli.NewInfo(cmd, args, cfg)
		ctx := cmd.Context()
		if err := cli.Run(ctx); err != nil {
			logrus.Fatalf("Ошибка: %v", err)
		}
		logrus.Debug("Просмотр состояния завершен")
	},
}

func init() {
	rootCmd.AddCommand(infoCmd)
}
