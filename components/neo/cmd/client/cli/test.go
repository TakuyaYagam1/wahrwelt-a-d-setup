package cli

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"syscall"
	"time"

	"github.com/c4t-but-s4d/neo/v2/internal/models"
	"github.com/c4t-but-s4d/neo/v2/internal/queue"
)

const (
	testOutputLimit = 1 << 20
	testFileLimit   = int64(512 << 20)
	testEntryLimit  = 10_000
	testTimeoutCode = 124
	testCancelCode  = 130
	testDirPrefix   = ".neo-test-"
)

// TestResult содержит ограниченный вывод локального запуска и найденные совпадения.
type TestResult struct {
	Stdout          []byte
	Stderr          []byte
	StdoutTruncated bool
	StderrTruncated bool
	ExitCode        int
	TimedOut        bool
	Flags           []string
}

type limitedBuffer struct {
	buf       bytes.Buffer
	limit     int
	truncated bool
}

func (b *limitedBuffer) Write(p []byte) (int, error) {
	remaining := b.limit - b.buf.Len()
	if remaining > len(p) {
		remaining = len(p)
	}
	if remaining > 0 {
		if _, err := b.buf.Write(p[:remaining]); err != nil {
			return 0, err
		}
	}
	if remaining < len(p) {
		b.truncated = true
	}
	return len(p), nil
}

func (b *limitedBuffer) Bytes() []byte {
	return b.buf.Bytes()
}

type copyLimits struct {
	entries int
	bytes   int64
}

func (limits *copyLimits) add(size int64) error {
	limits.entries++
	if limits.entries > testEntryLimit {
		return fmt.Errorf("в каталоге больше %d файлов и папок", testEntryLimit)
	}
	if size < 0 || size > testFileLimit-limits.bytes {
		return fmt.Errorf("данные превышают %d байт", testFileLimit)
	}
	limits.bytes += size
	return nil
}

// RunTest запускает локальную копию файла с адресом цели как единственным аргументом.
func RunTest(ctx context.Context, file, host string, timeout time.Duration, flagPattern string, includeDir bool) (TestResult, error) {
	workDir, err := os.Getwd()
	if err != nil {
		return TestResult{}, fmt.Errorf("не удалось получить рабочий каталог: %w", err)
	}
	return runTestAt(ctx, workDir, file, host, timeout, flagPattern, includeDir)
}

func runTestAt(ctx context.Context, workDir, file, host string, timeout time.Duration, flagPattern string, includeDir bool) (result TestResult, returnErr error) {
	if host == "" {
		return result, errors.New("адрес цели не может быть пустым")
	}
	if timeout <= 0 {
		return result, errors.New("тайм-аут должен быть больше нуля")
	}
	if flagPattern == "" {
		return result, errors.New("параметр --flag-regexp обязателен")
	}
	flagRegexp, err := regexp.Compile(flagPattern)
	if err != nil {
		return result, fmt.Errorf("неверное выражение --flag-regexp: %w", err)
	}

	absoluteFile, err := filepath.Abs(file)
	if err != nil {
		return result, fmt.Errorf("не удалось получить путь к файлу: %w", err)
	}
	info, err := os.Lstat(absoluteFile)
	if err != nil {
		return result, fmt.Errorf("не удалось проверить файл %s: %w", absoluteFile, err)
	}
	if !info.Mode().IsRegular() {
		return result, errors.New("файл задания должен быть обычным файлом, не ссылкой или каталогом")
	}
	resolvedFile, err := filepath.EvalSymlinks(absoluteFile)
	if err != nil || resolvedFile != absoluteFile {
		return result, errors.New("путь к файлу задания не должен проходить через символическую ссылку")
	}

	absoluteWorkDir, err := filepath.Abs(workDir)
	if err != nil {
		return result, fmt.Errorf("не удалось определить рабочий каталог: %w", err)
	}
	workingCopy, err := os.MkdirTemp(absoluteWorkDir, testDirPrefix)
	if err != nil {
		return result, fmt.Errorf("не удалось создать рабочую копию в %s: %w", absoluteWorkDir, err)
	}
	defer func() {
		if err := os.RemoveAll(workingCopy); err != nil {
			returnErr = errors.Join(returnErr, fmt.Errorf("не удалось удалить временную копию: %w", err))
		}
	}()

	entrypoint, err := copyTestInput(absoluteFile, workingCopy, includeDir)
	if err != nil {
		return result, fmt.Errorf("не удалось подготовить копию задания: %w", err)
	}

	stdout := &limitedBuffer{limit: testOutputLimit}
	stderr := &limitedBuffer{limit: testOutputLimit}
	job := queue.NewJob(
		models.NewExploit("local-test", 1, models.ExploitTypeRecurrent),
		models.NewTarget("local-test", host),
		entrypoint,
		workingCopy,
		nil,
		timeout,
		nil,
	)
	runCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	cmd := job.Command(runCtx)
	cmd.Stdout = stdout
	cmd.Stderr = stderr

	runErr := cmd.Run()
	result.Stdout = stdout.Bytes()
	result.Stderr = stderr.Bytes()
	result.StdoutTruncated = stdout.truncated
	result.StderrTruncated = stderr.truncated
	switch {
	case runErr == nil:
		result.ExitCode = 0
	case errors.Is(runCtx.Err(), context.DeadlineExceeded):
		result.ExitCode = testTimeoutCode
		result.TimedOut = true
	case errors.Is(runCtx.Err(), context.Canceled):
		result.ExitCode = testCancelCode
	default:
		var exitErr *exec.ExitError
		if !errors.As(runErr, &exitErr) {
			return result, fmt.Errorf("не удалось запустить задание: %w", runErr)
		}
		result.ExitCode = exitErr.ExitCode()
		if result.ExitCode < 0 {
			if status, ok := exitErr.Sys().(syscall.WaitStatus); ok && status.Signaled() {
				result.ExitCode = 128 + int(status.Signal())
			} else {
				result.ExitCode = 1
			}
		}
	}
	result.Flags = findFlags(flagRegexp, result.Stdout, result.Stderr)
	return result, nil
}

func copyTestInput(sourceFile, destination string, includeDir bool) (string, error) {
	sourceDir := filepath.Dir(sourceFile)
	entrypoint, err := filepath.Rel(sourceDir, sourceFile)
	if err != nil || !filepath.IsLocal(entrypoint) {
		return "", errors.New("небезопасный путь к точке входа")
	}
	limits := &copyLimits{}
	if !includeDir {
		destinationFile := filepath.Join(destination, entrypoint)
		if err := copyRegularFile(sourceFile, destinationFile, true, limits); err != nil {
			return "", err
		}
		return destinationFile, nil
	}

	rootInfo, err := os.Lstat(sourceDir)
	if err != nil {
		return "", fmt.Errorf("не удалось проверить каталог задания: %w", err)
	}
	if !rootInfo.IsDir() {
		return "", errors.New("родительская папка задания не является обычным каталогом")
	}
	resolvedRoot, err := filepath.EvalSymlinks(sourceDir)
	if err != nil || resolvedRoot != sourceDir {
		return "", errors.New("путь к папке задания не должен проходить через символическую ссылку")
	}

	destinationAbs, err := filepath.Abs(destination)
	if err != nil {
		return "", fmt.Errorf("не удалось определить путь рабочей копии: %w", err)
	}
	foundEntrypoint := false
	err = filepath.WalkDir(sourceDir, func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if filepath.Clean(path) == filepath.Clean(destinationAbs) {
			return filepath.SkipDir
		}
		if filepath.Clean(path) == filepath.Clean(sourceDir) {
			return nil
		}

		info, err := os.Lstat(path)
		if err != nil {
			return err
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("символическая ссылка запрещена: %s", entry.Name())
		}
		relative, err := filepath.Rel(sourceDir, path)
		if err != nil || !filepath.IsLocal(relative) {
			return fmt.Errorf("небезопасный путь в папке задания: %s", entry.Name())
		}
		target := filepath.Join(destinationAbs, relative)
		if info.IsDir() {
			if err := limits.add(0); err != nil {
				return err
			}
			return os.Mkdir(target, 0700)
		}
		if !info.Mode().IsRegular() {
			return fmt.Errorf("специальный файл запрещен: %s", entry.Name())
		}
		if err := copyRegularFile(path, target, filepath.Clean(path) == filepath.Clean(sourceFile), limits); err != nil {
			return err
		}
		if filepath.Clean(path) == filepath.Clean(sourceFile) {
			foundEntrypoint = true
		}
		return nil
	})
	if err != nil {
		return "", fmt.Errorf("обход каталога задания: %w", err)
	}
	if !foundEntrypoint {
		return "", errors.New("точка входа не найдена в родительской папке")
	}
	return filepath.Join(destinationAbs, entrypoint), nil
}

func copyRegularFile(source, destination string, entrypoint bool, limits *copyLimits) error {
	info, err := os.Lstat(source)
	if err != nil {
		return fmt.Errorf("не удалось проверить файл %s: %w", filepath.Base(source), err)
	}
	if !info.Mode().IsRegular() {
		return fmt.Errorf("обнаружен не обычный файл: %s", filepath.Base(source))
	}
	if err := limits.add(info.Size()); err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(destination), 0700); err != nil {
		return fmt.Errorf("не удалось создать папку рабочей копии: %w", err)
	}

	src, err := os.Open(source)
	if err != nil {
		return fmt.Errorf("не удалось открыть файл %s: %w", filepath.Base(source), err)
	}
	srcInfo, err := src.Stat()
	if err != nil || !os.SameFile(info, srcInfo) || !srcInfo.Mode().IsRegular() {
		_ = src.Close()
		return fmt.Errorf("файл изменился при копировании: %s", filepath.Base(source))
	}
	dst, err := os.OpenFile(destination, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		_ = src.Close()
		return fmt.Errorf("не удалось создать файл рабочей копии: %w", err)
	}
	_, copyErr := io.CopyN(dst, src, info.Size())
	if copyErr == nil {
		var extra [1]byte
		if n, readErr := src.Read(extra[:]); n != 0 || !errors.Is(readErr, io.EOF) {
			if readErr != nil && !errors.Is(readErr, io.EOF) {
				copyErr = readErr
			} else {
				copyErr = errors.New("размер файла изменился при копировании")
			}
		}
	}
	closeSourceErr := src.Close()
	closeDestinationErr := dst.Close()
	if copyErr != nil || closeSourceErr != nil || closeDestinationErr != nil {
		_ = os.Remove(destination)
		return errors.Join(copyErr, closeSourceErr, closeDestinationErr)
	}
	mode := os.FileMode(0600)
	if entrypoint || info.Mode().Perm()&0100 != 0 {
		mode |= 0100
	}
	if err := os.Chmod(destination, mode); err != nil {
		_ = os.Remove(destination)
		return fmt.Errorf("не удалось подготовить права файла: %w", err)
	}
	return nil
}

func findFlags(flagRegexp *regexp.Regexp, streams ...[]byte) []string {
	seen := make(map[string]struct{})
	var flags []string
	for _, stream := range streams {
		for _, match := range flagRegexp.FindAll(stream, -1) {
			flag := string(match)
			if flag == "" {
				continue
			}
			if _, exists := seen[flag]; exists {
				continue
			}
			seen[flag] = struct{}{}
			flags = append(flags, flag)
		}
	}
	return flags
}

// WriteTo показывает оба потока отдельно, код завершения и уникальные совпадения.
func (result TestResult) WriteTo(stdout, stderr io.Writer) error {
	if _, err := fmt.Fprintln(stdout, "stdout:"); err != nil {
		return err
	}
	if err := writeOutput(stdout, result.Stdout, result.StdoutTruncated, "stdout"); err != nil {
		return err
	}
	if _, err := fmt.Fprintln(stderr, "stderr:"); err != nil {
		return err
	}
	if err := writeOutput(stderr, result.Stderr, result.StderrTruncated, "stderr"); err != nil {
		return err
	}
	if result.TimedOut {
		if _, err := fmt.Fprintln(stdout, "тайм-аут: истекло время ожидания"); err != nil {
			return err
		}
	}
	if _, err := fmt.Fprintf(stdout, "код выхода: %d\nфлаги:\n", result.ExitCode); err != nil {
		return err
	}
	for _, flag := range result.Flags {
		if _, err := fmt.Fprintf(stdout, "  %s\n", flag); err != nil {
			return err
		}
	}
	return nil
}

func writeOutput(dst io.Writer, output []byte, truncated bool, stream string) error {
	if len(output) > 0 {
		if _, err := dst.Write(output); err != nil {
			return err
		}
		if output[len(output)-1] != '\n' {
			if _, err := fmt.Fprintln(dst); err != nil {
				return err
			}
		}
	}
	if truncated {
		_, err := fmt.Fprintf(dst, "[вывод %s обрезан после 1 МиБ]\n", stream)
		return err
	}
	return nil
}
