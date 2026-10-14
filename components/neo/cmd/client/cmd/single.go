package cmd

import (
	"github.com/sirupsen/logrus"
	"github.com/spf13/cobra"

	"github.com/c4t-but-s4d/neo/v2/cmd/client/cli"
	"github.com/c4t-but-s4d/neo/v2/internal/client"
)

// singleRunCmd описывает разовый запуск эксплойта.
var singleRunCmd = &cobra.Command{
	Use:   "single",
	Short: "Немедленно запустить эксплойт один раз для всех команд",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		cfg := client.MustUnmarshalConfig()
		cli := cli.NewSingleRun(cmd, args, cfg)
		ctx := cmd.Context()
		if err := cli.Run(ctx); err != nil {
			logrus.Fatalf("Не удалось выполнить разовый запуск: %v", err)
		}
		logrus.Debug("Разовый запуск завершен")
	},
}

func init() {
	rootCmd.AddCommand(singleRunCmd)
}
