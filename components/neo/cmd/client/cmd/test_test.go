package cmd

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/spf13/cobra"
)

func TestTestCommandFlagsAndArguments(t *testing.T) {
	if testCmd.Use != "test FILE HOST" {
		t.Fatalf("команда = %q", testCmd.Use)
	}
	if err := testCmd.Args(testCmd, []string{"job.py", "host"}); err != nil {
		t.Fatalf("команда отклонила допустимые аргументы: %v", err)
	}
	if err := testCmd.Args(testCmd, []string{"job.py"}); err == nil {
		t.Fatal("команда приняла запуск без адреса цели")
	}
	if err := testCmd.Args(testCmd, []string{"job.py", "host", "extra"}); err == nil {
		t.Fatal("команда приняла лишний аргумент")
	}
	timeout := testCmd.Flags().Lookup("timeout")
	if timeout == nil || timeout.DefValue != (30*time.Second).String() {
		t.Fatalf("значение тайм-аута по умолчанию = %v", timeout)
	}
	pattern := testCmd.Flags().Lookup("flag-regexp")
	if pattern == nil {
		t.Fatal("параметр --flag-regexp отсутствует")
	}
	includeDir := testCmd.Flags().Lookup("dir")
	if includeDir == nil || includeDir.DefValue != "false" {
		t.Fatal("параметр --dir отсутствует или включен по умолчанию")
	}
}

func TestTestCommandReportsMissingPatternInRussian(t *testing.T) {
	command := &cobra.Command{}
	command.SetContext(context.Background())
	command.Flags().Duration("timeout", time.Second, "")
	command.Flags().String("flag-regexp", "", "")
	command.Flags().Bool("dir", false, "")
	var stderr bytes.Buffer
	command.SetErr(&stderr)
	var exitCode int
	runTestCommand(command, []string{"unused.py", "target"}, func(code int) { exitCode = code })
	if exitCode != 1 {
		t.Fatalf("код выхода команды = %d, ожидался 1", exitCode)
	}
	if !bytes.Contains(stderr.Bytes(), []byte("--flag-regexp обязателен")) {
		t.Fatalf("ошибка об обязательном параметре не показана по-русски: %q", stderr.String())
	}
}

func TestTestCommandPropagatesJobExitCode(t *testing.T) {
	jobFile := filepath.Join(t.TempDir(), "job.sh")
	if err := os.WriteFile(jobFile, []byte("#!/bin/sh\nexit 7\n"), 0700); err != nil {
		t.Fatal(err)
	}

	command := &cobra.Command{}
	command.SetContext(context.Background())
	command.Flags().Duration("timeout", time.Second, "")
	command.Flags().String("flag-regexp", `FLAG[0-9]+=`, "")
	command.Flags().Bool("dir", false, "")
	var stdout, stderr bytes.Buffer
	command.SetOut(&stdout)
	command.SetErr(&stderr)
	var exitCode int
	runTestCommand(command, []string{jobFile, "target"}, func(code int) { exitCode = code })

	if exitCode != 7 {
		t.Fatalf("код выхода команды = %d, ожидался 7", exitCode)
	}
	if !bytes.Contains(stdout.Bytes(), []byte("код выхода: 7\n")) {
		t.Fatalf("в stdout нет кода завершения задания: %q", stdout.String())
	}
}
