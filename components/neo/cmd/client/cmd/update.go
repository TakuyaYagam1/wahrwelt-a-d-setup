package cmd

import (
	"time"

	"github.com/sirupsen/logrus"
	"github.com/spf13/cobra"

	"github.com/c4t-but-s4d/neo/v2/cmd/client/cli"
	"github.com/c4t-but-s4d/neo/v2/internal/client"
)

// updateCmd описывает команду изменения настроек эксплойта.
var updateCmd = &cobra.Command{
	Use:     "update",
	Short:   "Изменить настройки эксплойта",
	Example: "neo update exploit_name -i 2m -t 2m",
	Args:    cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		cfg := client.MustUnmarshalConfig()
		cli := cli.NewUpdateCLI(cmd, args, cfg)
		ctx := cmd.Context()
		if err := cli.Run(ctx); err != nil {
			logrus.Fatalf("Не удалось изменить настройки эксплойта: %v", err)
		}
		logrus.Debug("Изменение настроек завершено")
	},
}

func init() {
	rootCmd.AddCommand(updateCmd)
	updateCmd.PersistentFlags().DurationP("interval", "i", time.Second*30, "интервал запуска")
	updateCmd.PersistentFlags().DurationP("timeout", "t", time.Second*30, "тайм-аут одного запуска")
	updateCmd.PersistentFlags().BoolP("endless", "e", false, "запускать эксплойт в бесконечном режиме")
	updateCmd.PersistentFlags().Bool("disabled", false, "отключить эксплойт")
}
