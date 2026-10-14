package cmd

import (
	"runtime"

	"github.com/sirupsen/logrus"
	"github.com/spf13/cobra"

	"github.com/c4t-but-s4d/neo/v2/cmd/client/cli"
	"github.com/c4t-but-s4d/neo/v2/internal/client"
)

// runCmd описывает команду запуска клиента.
var runCmd = &cobra.Command{
	Use:   "run",
	Short: "Запустить клиент Neo",
	Run: func(cmd *cobra.Command, args []string) {
		cfg := client.MustUnmarshalConfig()
		cli := cli.NewRun(cmd, args, cfg)
		ctx := cmd.Context()
		if err := cli.Run(ctx); err != nil {
			logrus.Fatalf("Ошибка: %v", err)
		}
		logrus.Debug("Работа завершена")
	},
}

func init() {
	rootCmd.AddCommand(runCmd)
	runCmd.Flags().IntP("jobs", "j", runtime.NumCPU()*cli.JobsPerCPU, "число обычных заданий")
	runCmd.Flags().IntP("endless-jobs", "e", 0, "число заданий бесконечного режима; 0 отключает этот режим")
	runCmd.Flags().Float64(
		"timeout-autoscale-target",
		1.5,
		"предельная загрузка обычных заданий; масштабирование тайм-аутов удерживает ее ниже этого значения."+
			" Значение 0 отключает масштабирование",
	)
}
