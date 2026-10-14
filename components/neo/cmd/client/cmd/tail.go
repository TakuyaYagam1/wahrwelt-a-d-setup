package cmd

import (
	"github.com/sirupsen/logrus"
	"github.com/spf13/cobra"

	"github.com/c4t-but-s4d/neo/v2/cmd/client/cli"
	"github.com/c4t-but-s4d/neo/v2/internal/client"
)

// tailCmd описывает команду просмотра журналов эксплойта.
var tailCmd = &cobra.Command{
	Use:   "tail",
	Short: "Показать журнал эксплойта",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		cfg := client.MustUnmarshalConfig()
		cli := cli.NewTail(cmd, args, cfg)
		ctx := cmd.Context()
		if err := cli.Run(ctx); err != nil {
			logrus.Fatalf("Не удалось показать журнал: %v", err)
		}
		logrus.Debug("Просмотр журнала завершен")
	},
}

func init() {
	rootCmd.AddCommand(tailCmd)

	tailCmd.PersistentFlags().Int64("version", 0, "версия эксплойта")
	tailCmd.PersistentFlags().IntP("count", "n", -1, "число строк; -1 означает показать все")
}
