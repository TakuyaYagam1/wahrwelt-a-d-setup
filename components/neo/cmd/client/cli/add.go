package cli

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/sirupsen/logrus"
	"github.com/spf13/cobra"
	"google.golang.org/protobuf/types/known/durationpb"

	"github.com/c4t-but-s4d/neo/v2/internal/client"
	"github.com/c4t-but-s4d/neo/v2/pkg/archive"
	epb "github.com/c4t-but-s4d/neo/v2/proto/go/exploits"
)

type addCLI struct {
	*baseCLI
	path      string
	isArchive bool
	exploitID string
	runEvery  time.Duration
	timeout   time.Duration
	endless   bool
	disabled  bool
	yes       bool
}

func NewAdd(cmd *cobra.Command, args []string, cfg *client.Config) NeoCLI {
	c := &addCLI{
		baseCLI: &baseCLI{cfg: cfg},
		path:    args[0],
	}

	var err error
	if c.exploitID, err = cmd.Flags().GetString("id"); err != nil {
		logrus.Fatalf("Не удалось прочитать идентификатор эксплойта: %v", err)
	}
	if c.isArchive, err = cmd.Flags().GetBool("dir"); err != nil {
		logrus.Fatalf("Не удалось прочитать параметр каталога: %v", err)
	}
	if c.runEvery, err = cmd.Flags().GetDuration("interval"); err != nil {
		logrus.Fatalf("Не удалось прочитать интервал запуска: %v", err)
	}
	if c.timeout, err = cmd.Flags().GetDuration("timeout"); err != nil {
		logrus.Fatalf("Не удалось прочитать тайм-аут запуска: %v", err)
	}
	if c.endless, err = cmd.Flags().GetBool("endless"); err != nil {
		logrus.Fatalf("Не удалось прочитать параметр бесконечного режима: %v", err)
	}
	if c.disabled, err = cmd.Flags().GetBool("disabled"); err != nil {
		logrus.Fatalf("Не удалось прочитать параметр отключения: %v", err)
	}
	if c.yes, err = cmd.Flags().GetBool("yes"); err != nil {
		logrus.Fatalf("Не удалось прочитать параметр --yes: %v", err)
	}
	return c
}

func (ac *addCLI) Run(ctx context.Context) error {
	_, err := os.Stat(ac.path)
	if err != nil {
		return fmt.Errorf("не удалось проверить файл %s: %w", ac.path, err)
	}
	// Заменить путь на абсолютный.
	if ac.path, err = filepath.Abs(ac.path); err != nil {
		return fmt.Errorf("не удалось получить абсолютный путь: %w", err)
	}

	if errs := ac.validateEntry(ac.path); len(errs) > 0 {
		for _, v := range errs {
			logrus.Errorf("%v", v)
		}
		return errors.New("эксплойт некорректен")
	}

	dir, file := path.Split(ac.path)
	if ac.exploitID == "" {
		ac.exploitID = file
	}
	logrus.Infof("Добавление эксплойта с id = %s", ac.exploitID)

	c, err := ac.client()
	if err != nil {
		return fmt.Errorf("не удалось создать клиент: %w", err)
	}
	state, err := c.GetServerState(ctx)
	if err != nil {
		return fmt.Errorf("не удалось получить конфигурацию сервера: %w", err)
	}

	if getExploitFromState(state, ac.exploitID) != nil {
		confirmed, err := confirmNewVersion(os.Stdin, os.Stdout, ac.yes)
		if err != nil {
			return fmt.Errorf("не удалось прочитать ответ: %w", err)
		}
		if !confirmed {
			logrus.Fatal("Операция отменена")
		}
	}

	var f *os.File
	if ac.isArchive {
		f, err = os.CreateTemp("", "ARCHIVE")
		if err != nil {
			return fmt.Errorf("не удалось создать временный файл: %w", err)
		}

		defer func() {
			_ = os.Remove(f.Name())
		}()
		if err := archive.Tar(dir, f); err != nil {
			return fmt.Errorf("не удалось создать архив tar.gz: %w", err)
		}

		// Переместить указатель в начало файла перед чтением.
		if _, err := f.Seek(0, io.SeekStart); err != nil {
			return fmt.Errorf("не удалось переместить указатель в архиве: %w", err)
		}
	} else {
		f, err = os.Open(ac.path)
		if err != nil {
			return fmt.Errorf("не удалось открыть файл эксплойта: %w", err)
		}
	}
	defer func() {
		_ = f.Close()
	}()

	fileInfo, err := c.UploadFile(ctx, f)
	if err != nil {
		return fmt.Errorf("не удалось загрузить файл эксплойта: %w", err)
	}

	exState := &epb.ExploitState{
		ExploitId: ac.exploitID,
		File:      fileInfo,
		Config: &epb.ExploitConfiguration{
			Entrypoint: file,
			IsArchive:  ac.isArchive,
			RunEvery:   durationpb.New(ac.runEvery),
			Timeout:    durationpb.New(ac.timeout),
			Endless:    ac.endless,
			Disabled:   ac.disabled,
		},
	}
	newState, err := c.UpdateExploit(ctx, exState)
	if err != nil {
		return fmt.Errorf("не удалось обновить эксплойт: %w", err)
	}
	logrus.Infof("Эксплойт %s обновлен до версии %d", newState.GetExploitId(), newState.GetVersion())
	return nil
}

func confirmNewVersion(input io.Reader, output io.Writer, assumeYes bool) (bool, error) {
	if assumeYes {
		return true, nil
	}
	if _, err := fmt.Fprintln(output, "Эксплойт с таким id уже есть. Добавить новую версию? да/нет"); err != nil {
		return false, fmt.Errorf("не удалось показать запрос подтверждения: %w", err)
	}
	var response string
	if _, err := fmt.Fscanln(input, &response); err != nil {
		return false, err
	}
	answer := strings.ToLower(strings.TrimSpace(response))
	return answer == "y" || answer == "yes" || answer == "д" || answer == "да", nil
}

func (ac *addCLI) validateEntry(f string) (errors []string) {
	data, err := os.ReadFile(f)
	if err != nil {
		errors = append(errors, err.Error())
		return
	}
	if !isBinary(data) {
		if !bytes.HasPrefix(data, []byte("#!")) {
			desc := fmt.Sprintf(
				"Добавьте в первую строку скрипта shebang, например %s",
				"#!/usr/bin/env python3",
			)
			errors = append(errors, desc)
		}

		// Для Python задана переменная PYTHONUNBUFFERED=1, поэтому принудительный сброс не нужен.
		if !bytes.Contains(data, []byte("#!/usr/bin/env python")) {
			re := regexp.MustCompile(`(?m)flush[(=]`)
			if !re.Match(data) {
				desc := "Сбрасывайте вывод явно, например print(..., flush=True) в Python"
				errors = append(errors, desc)
			}
		}
	}
	return
}
