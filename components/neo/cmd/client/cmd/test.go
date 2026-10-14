package cmd

import (
	"fmt"
	"os"
	"time"

	"github.com/spf13/cobra"

	"github.com/c4t-but-s4d/neo/v2/cmd/client/cli"
)

var testCmd = &cobra.Command{
	Use:   "test FILE HOST",
	Short: "Локально запустить файл для одной цели",
	Long:  "Запускает локальный файл без подключения к Neo или Farm и показывает вывод, код завершения и совпадения с заданным выражением.",
	Args: func(cmd *cobra.Command, args []string) error {
		if len(args) != 2 {
			return fmt.Errorf("ожидаются ровно два аргумента: файл задания и адрес цели")
		}
		return nil
	},
	Run: func(cmd *cobra.Command, args []string) {
		runTestCommand(cmd, args, os.Exit)
	},
}

func runTestCommand(cmd *cobra.Command, args []string, exit func(int)) {
	timeout, err := cmd.Flags().GetDuration("timeout")
	if err != nil {
		_, _ = fmt.Fprintf(cmd.ErrOrStderr(), "Не удалось прочитать тайм-аут: %v\n", err)
		exit(1)
		return
	}
	flagPattern, err := cmd.Flags().GetString("flag-regexp")
	if err != nil {
		_, _ = fmt.Fprintf(cmd.ErrOrStderr(), "Не удалось прочитать выражение флага: %v\n", err)
		exit(1)
		return
	}
	includeDir, err := cmd.Flags().GetBool("dir")
	if err != nil {
		_, _ = fmt.Fprintf(cmd.ErrOrStderr(), "Не удалось прочитать параметр --dir: %v\n", err)
		exit(1)
		return
	}
	result, err := cli.RunTest(cmd.Context(), args[0], args[1], timeout, flagPattern, includeDir)
	if err != nil {
		_, _ = fmt.Fprintf(cmd.ErrOrStderr(), "Локальный запуск не выполнен: %v\n", err)
		exit(1)
		return
	}
	if err := result.WriteTo(cmd.OutOrStdout(), cmd.ErrOrStderr()); err != nil {
		_, _ = fmt.Fprintf(cmd.ErrOrStderr(), "Не удалось показать результат: %v\n", err)
		exit(1)
		return
	}
	if result.ExitCode != 0 {
		exit(result.ExitCode)
	}
}

func init() {
	rootCmd.AddCommand(testCmd)
	testCmd.Flags().Duration("timeout", 30*time.Second, "тайм-аут локального запуска")
	testCmd.Flags().String("flag-regexp", "", "обязательное регулярное выражение для поиска флагов в выводе")
	testCmd.Flags().Bool("dir", false, "скопировать папку с соседними файлами для запуска")
}
