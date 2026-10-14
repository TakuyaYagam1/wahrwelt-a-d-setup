package cmd

import (
	"runtime"

	"github.com/sirupsen/logrus"
	"github.com/spf13/cobra"

	"github.com/c4t-but-s4d/neo/v2/cmd/client/cli"
	"github.com/c4t-but-s4d/neo/v2/internal/client"
)

// dryRunCmd описывает локальный запуск эксплойта.
var dryRunCmd = &cobra.Command{
	Use:   "dry-run",
	Short: "Запустить эксплойт локально",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		cfg := client.MustUnmarshalConfig()
		cli := cli.NewDryRun(cmd, args, cfg)
		ctx := cmd.Context()
		if err := cli.Run(ctx); err != nil {
			logrus.Fatalf("Ошибка: %v", err)
		}
		logrus.Debug("Локальный запуск завершен")
	},
}

func init() {
	rootCmd.AddCommand(dryRunCmd)
	dryRunCmd.Flags().StringP("team_ip", "p", "", "IP команды для запуска")
	dryRunCmd.Flags().StringP("team_id", "d", "", "идентификатор команды для запуска")
	dryRunCmd.Flags().IntP("jobs", "j", runtime.NumCPU()*cli.JobsPerCPU, "число параллельных заданий")
}
