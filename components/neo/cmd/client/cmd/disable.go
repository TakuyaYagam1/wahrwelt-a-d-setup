package cmd

import (
	"github.com/sirupsen/logrus"
	"github.com/spf13/cobra"

	"github.com/c4t-but-s4d/neo/v2/cmd/client/cli"
	"github.com/c4t-but-s4d/neo/v2/internal/client"
)

// disableCmd описывает команду отключения эксплойта.
var disableCmd = &cobra.Command{
	Use:   "disable",
	Short: "Отключить эксплойт по идентификатору",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		cfg := client.MustUnmarshalConfig()
		cli := cli.NewSetDisabled(cmd, args, cfg, true)
		ctx := cmd.Context()
		if err := cli.Run(ctx); err != nil {
			logrus.Fatalf("Не удалось отключить эксплойт: %v", err)
		}
		logrus.Debug("Отключение завершено")
	},
}

func init() {
	rootCmd.AddCommand(disableCmd)
}
