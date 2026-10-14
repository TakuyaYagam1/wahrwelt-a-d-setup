package cmd

import (
	"github.com/sirupsen/logrus"
	"github.com/spf13/cobra"

	"github.com/c4t-but-s4d/neo/v2/cmd/client/cli"
	"github.com/c4t-but-s4d/neo/v2/internal/client"
)

// enableCmd описывает команду включения эксплойта.
var enableCmd = &cobra.Command{
	Use:   "enable",
	Short: "Включить эксплойт по идентификатору",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		cfg := client.MustUnmarshalConfig()
		cli := cli.NewSetDisabled(cmd, args, cfg, false)
		ctx := cmd.Context()
		if err := cli.Run(ctx); err != nil {
			logrus.Fatalf("Не удалось включить эксплойт: %v", err)
		}
		logrus.Debug("Включение завершено")
	},
}

func init() {
	rootCmd.AddCommand(enableCmd)
}
