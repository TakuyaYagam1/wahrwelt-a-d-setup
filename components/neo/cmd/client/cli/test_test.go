package cli

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

func TestRunTestCapturesStreamsExitCodeFlagsAndFiltersSecrets(t *testing.T) {
	secrets := []string{"NEO_GRPC_AUTH_KEY", "NEO_WORKER_KEY", "NEO_KEY", "GRPC_AUTH_KEY", "FARM_PASSWORD", "NEO_FARM_PASSWORD"}
	for _, name := range secrets {
		t.Setenv(name, "test-secret-"+name)
	}
	work := t.TempDir()
	file := writeTestProgram(t, "#!/bin/sh\nprintf 'stdout: ABC123=\\n'\nprintf 'stderr: ABC123= DEF456=\\n' >&2\n"+
		"printf 'credentials=%s|%s|%s|%s|%s|%s\\n' \"${NEO_GRPC_AUTH_KEY-unset}\" \"${NEO_WORKER_KEY-unset}\" \"${NEO_KEY-unset}\" \"${GRPC_AUTH_KEY-unset}\" \"${FARM_PASSWORD-unset}\" \"${NEO_FARM_PASSWORD-unset}\" >&2\nexit 7\n")

	result, err := runTestAt(context.Background(), work, file, "192.0.2.10", time.Second, `[A-Z0-9]{6}=`, false)
	if err != nil {
		t.Fatalf("ошибка локального запуска: %v", err)
	}
	if result.ExitCode != 7 {
		t.Fatalf("код выхода = %d, ожидался 7", result.ExitCode)
	}
	if got := string(result.Stdout); got != "stdout: ABC123=\n" {
		t.Fatalf("stdout = %q", got)
	}
	if got := string(result.Stderr); !strings.Contains(got, "stderr: ABC123= DEF456=") || !strings.Contains(got, "credentials=unset|unset|unset|unset|unset|unset") {
		t.Fatalf("stderr = %q", got)
	}
	for _, name := range secrets {
		if strings.Contains(string(result.Stderr), "test-secret-"+name) {
			t.Fatalf("секрет %s попал в окружение запускаемого задания", name)
		}
	}
	if got, want := strings.Join(result.Flags, ","), "ABC123=,DEF456="; got != want {
		t.Fatalf("флаги = %q, ожидались %q", got, want)
	}
}

func TestRunTestCopiesSiblingFilesForWritesAndCleansUp(t *testing.T) {
	stage := t.TempDir()
	work := t.TempDir()
	file := filepath.Join(stage, "job.sh")
	if err := os.WriteFile(file, []byte("#!/bin/sh\nset -eu\ncat resource.txt\nprintf 'host=%s\\n' \"$1\"\nprintf 'created\\n' > result.txt\nprintf 'FLAG123=\\n'\n"), 0444); err != nil {
		t.Fatal(err)
	}
	resource := filepath.Join(stage, "resource.txt")
	if err := os.WriteFile(resource, []byte("sibling resource\n"), 0444); err != nil {
		t.Fatal(err)
	}

	result, err := runTestAt(context.Background(), work, file, "target.example", time.Second, `FLAG[0-9]+=`, true)
	if err != nil {
		t.Fatalf("ошибка запуска копии папки: %v", err)
	}
	if result.ExitCode != 0 {
		t.Fatalf("код выхода = %d, ожидался 0; stderr = %q", result.ExitCode, result.Stderr)
	}
	if got, want := string(result.Stdout), "sibling resource\nhost=target.example\nFLAG123=\n"; got != want {
		t.Fatalf("stdout = %q, ожидалось %q", got, want)
	}
	if got, want := strings.Join(result.Flags, ","), "FLAG123="; got != want {
		t.Fatalf("найденные флаги = %q, ожидался %q", got, want)
	}
	if _, err := os.Stat(filepath.Join(stage, "result.txt")); !os.IsNotExist(err) {
		t.Fatalf("исходная папка изменена: stat result.txt = %v", err)
	}
	entries, err := os.ReadDir(work)
	if err != nil {
		t.Fatalf("не удалось проверить очистку рабочей папки: %v", err)
	}
	if len(entries) != 0 {
		t.Fatalf("временная копия осталась после запуска: %v", entries)
	}
}

func TestRunTestTimeoutUses124(t *testing.T) {
	work := t.TempDir()
	file := writeTestProgram(t, "#!/bin/sh\nsleep 5 &\nwait\n")

	started := time.Now()
	result, err := runTestAt(context.Background(), work, file, "target", 50*time.Millisecond, `FLAG[0-9]+=`, false)
	if err != nil {
		t.Fatalf("ошибка локального запуска: %v", err)
	}
	if result.ExitCode != testTimeoutCode || !result.TimedOut {
		t.Fatalf("результат {код:%d тайм-аут:%t}, ожидался тайм-аут 124", result.ExitCode, result.TimedOut)
	}
	if time.Since(started) > 2*time.Second {
		t.Fatal("дочерний процесс не остановился после тайм-аута")
	}
}

func TestRunTestCapsEachOutputStream(t *testing.T) {
	work := t.TempDir()
	file := writeTestProgram(t, fmt.Sprintf("#!/bin/sh\nhead -c %d /dev/zero | tr '\\000' x\nhead -c %d /dev/zero | tr '\\000' y >&2\n", testOutputLimit+1, testOutputLimit+1))

	result, err := runTestAt(context.Background(), work, file, "target", 5*time.Second, `FLAG[0-9]+=`, false)
	if err != nil {
		t.Fatalf("ошибка локального запуска: %v", err)
	}
	if len(result.Stdout) != testOutputLimit || len(result.Stderr) != testOutputLimit {
		t.Fatalf("длины вывода = (%d, %d), обрезан = (%t, %t), ожидалось по %d байт", len(result.Stdout), len(result.Stderr), result.StdoutTruncated, result.StderrTruncated, testOutputLimit)
	}
	if !result.StdoutTruncated || !result.StderrTruncated {
		t.Fatal("оба потока должны иметь отметку об обрезке")
	}
}

func TestRunTestRejectsInvalidInputs(t *testing.T) {
	work := t.TempDir()
	file := writeTestProgram(t, "#!/bin/sh\nexit 0\n")
	tests := []struct {
		name    string
		file    string
		host    string
		timeout time.Duration
		pattern string
	}{
		{name: "пустой адрес", file: file, timeout: time.Second, pattern: `FLAG[0-9]+=`},
		{name: "неположительный тайм-аут", file: file, host: "target", pattern: `FLAG[0-9]+=`},
		{name: "нет выражения флага", file: file, host: "target", timeout: time.Second},
		{name: "неверное выражение", file: file, host: "target", timeout: time.Second, pattern: "["},
		{name: "нет файла", file: filepath.Join(t.TempDir(), "missing"), host: "target", timeout: time.Second, pattern: `FLAG[0-9]+=`},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if _, err := runTestAt(context.Background(), work, test.file, test.host, test.timeout, test.pattern, false); err == nil {
				t.Fatal("ошибка проверки входных данных отсутствует")
			}
		})
	}
}

func TestRunTestRejectsSymlinksAndSpecialFiles(t *testing.T) {
	for _, kind := range []string{"символическая ссылка", "FIFO"} {
		t.Run(kind, func(t *testing.T) {
			stage := t.TempDir()
			work := t.TempDir()
			file := filepath.Join(stage, "job.sh")
			if err := os.WriteFile(file, []byte("#!/bin/sh\nexit 0\n"), 0700); err != nil {
				t.Fatal(err)
			}
			switch kind {
			case "символическая ссылка":
				if err := os.Symlink("job.sh", filepath.Join(stage, "linked.sh")); err != nil {
					t.Fatal(err)
				}
			case "FIFO":
				if err := syscall.Mkfifo(filepath.Join(stage, "pipe"), 0600); err != nil {
					t.Fatal(err)
				}
			}
			if _, err := runTestAt(context.Background(), work, file, "target", time.Second, `FLAG[0-9]+=`, true); err == nil {
				t.Fatal("опасный файл должен отклоняться")
			}
			entries, err := os.ReadDir(work)
			if err != nil {
				t.Fatalf("не удалось проверить очистку: %v", err)
			}
			if len(entries) != 0 {
				t.Fatalf("временная копия осталась после ошибки: %v", entries)
			}
		})
	}
}

func TestCopyLimitsBoundTotalBytesAndEntries(t *testing.T) {
	var limits copyLimits
	if err := limits.add(testFileLimit); err != nil {
		t.Fatalf("допустимый предел байтов отклонен: %v", err)
	}
	if err := limits.add(1); err == nil {
		t.Fatal("превышение предела байтов не обнаружено")
	}

	limits = copyLimits{}
	for i := 0; i < testEntryLimit; i++ {
		if err := limits.add(0); err != nil {
			t.Fatalf("допустимое число записей отклонено: %v", err)
		}
	}
	if err := limits.add(0); err == nil {
		t.Fatal("превышение предела записей не обнаружено")
	}
}

func TestLimitedBufferKeepsDrainingAfterLimit(t *testing.T) {
	buffer := &limitedBuffer{limit: testOutputLimit}
	data := make([]byte, testOutputLimit+1)
	if n, err := buffer.Write(data); err != nil || n != len(data) {
		t.Fatalf("Write() = (%d, %v), ожидалось (%d, nil)", n, err, len(data))
	}
	if buffer.buf.Len() != testOutputLimit || !buffer.truncated {
		t.Fatalf("длина буфера = %d, обрезан = %t", buffer.buf.Len(), buffer.truncated)
	}
}

func writeTestProgram(t *testing.T, content string) string {
	t.Helper()
	file := filepath.Join(t.TempDir(), "job.sh")
	if err := os.WriteFile(file, []byte(content), 0700); err != nil {
		t.Fatal(err)
	}
	return file
}
