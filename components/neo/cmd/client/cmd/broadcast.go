package cmd

import (
	"github.com/sirupsen/logrus"
	"github.com/spf13/cobra"

	"github.com/c4t-but-s4d/neo/v2/cmd/client/cli"
	"github.com/c4t-but-s4d/neo/v2/internal/client"
)

// broadcastCmd описывает команду отправки команды worker-клиентам.
var broadcastCmd = &cobra.Command{
	Use:   "broadcast",
	Short: "Выполнить команду на всех подключенных клиентах",
	Run: func(cmd *cobra.Command, args []string) {
		cfg := client.MustUnmarshalConfig()
		cli := cli.NewBroadcast(cmd, args, cfg)
		ctx := cmd.Context()
		if err := cli.Run(ctx); err != nil {
			logrus.Fatalf("Не удалось разослать команду: %v", err)
		}
		logrus.Debug("Рассылка завершена")
	},
}

func init() {
	rootCmd.AddCommand(broadcastCmd)
	broadcastCmd.Flags().StringP("command", "r", "", "команда для запуска")
}
