package cmd

import (
	"time"

	"github.com/sirupsen/logrus"
	"github.com/spf13/cobra"

	"github.com/c4t-but-s4d/neo/v2/cmd/client/cli"
	"github.com/c4t-but-s4d/neo/v2/internal/client"
)

// addCmd описывает команду добавления эксплойта.
var addCmd = &cobra.Command{
	Use:   "add",
	Short: "Добавить эксплойт",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		cfg := client.MustUnmarshalConfig()
		cli := cli.NewAdd(cmd, args, cfg)
		ctx := cmd.Context()
		if err := cli.Run(ctx); err != nil {
			logrus.Fatalf("Не удалось добавить эксплойт: %v", err)
		}
		logrus.Debug("Добавление завершено")
	},
}

func init() {
	rootCmd.AddCommand(addCmd)

	addCmd.PersistentFlags().String("id", "", "имя эксплойта")
	addCmd.PersistentFlags().BoolP("dir", "d", false, "добавить эксплойт как каталог")
	addCmd.PersistentFlags().DurationP("interval", "i", time.Second*30, "интервал запуска")
	addCmd.PersistentFlags().DurationP("timeout", "t", time.Second*30, "тайм-аут одного запуска")
	addCmd.PersistentFlags().BoolP("endless", "e", false, "запускать эксплойт в бесконечном режиме")
	addCmd.PersistentFlags().Bool("disabled", false, "добавить эксплойт в отключенном состоянии")
	addCmd.PersistentFlags().Bool("yes", false, "подтвердить добавление новой версии без вопроса")
}
