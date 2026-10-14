package fs

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/google/uuid"
)

type fileInterface interface {
	io.ReadWriteCloser
	Name() string
}

type filesystem interface {
	Create(string) (fileInterface, error)
	Open(string) (fileInterface, error)
	Remove(string) error
}

type osFs struct {
	root *os.Root
}

func newOsFs(dir string) (*osFs, error) {
	abs, err := filepath.Abs(dir)
	if err != nil {
		return nil, fmt.Errorf("определение пути каталога %s: %w", dir, err)
	}
	if err := os.MkdirAll(abs, 0700); err != nil {
		return nil, fmt.Errorf("создание каталога %s: %w", dir, err)
	}
	root, err := os.OpenRoot(abs)
	if err != nil {
		return nil, fmt.Errorf("открытие каталога %s: %w", dir, err)
	}
	return &osFs{root: root}, nil
}

func (o *osFs) Create(f string) (fileInterface, error) {
	if err := validateFileID(f); err != nil {
		return nil, err
	}
	fi, err := o.root.OpenFile(f, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return nil, fmt.Errorf("создание файла %s в %s: %w", f, o.root.Name(), err)
	}
	return fi, nil
}

func (o *osFs) Open(f string) (fileInterface, error) {
	if err := validateFileID(f); err != nil {
		return nil, err
	}
	info, err := o.root.Lstat(f)
	if err != nil {
		return nil, fmt.Errorf("открытие файла %s в %s: %w", f, o.root.Name(), err)
	}
	if !info.Mode().IsRegular() {
		return nil, fmt.Errorf("путь %s в %s не является обычным файлом", f, o.root.Name())
	}
	fi, err := o.root.Open(f)
	if err != nil {
		return nil, fmt.Errorf("повторное открытие файла %s в %s: %w", f, o.root.Name(), err)
	}
	return fi, nil
}

func (o *osFs) Remove(f string) error {
	if err := validateFileID(f); err != nil {
		return err
	}
	if err := o.root.Remove(f); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("удаление файла %s в %s: %w", f, o.root.Name(), err)
	}
	return nil
}

func validateFileID(id string) error {
	parsed, err := uuid.Parse(id)
	if err != nil || parsed.String() != id || strings.ContainsAny(id, `/\\`) {
		return fmt.Errorf("неверный идентификатор файла")
	}
	return nil
}
