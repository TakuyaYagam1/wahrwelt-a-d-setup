package archive

import (
	"archive/tar"
	"compress/gzip"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/sirupsen/logrus"
)

const (
	maxArchiveEntries  = 10000
	maxArchiveExpanded = 512 << 20
)

func Untar(dst string, r io.Reader) error {
	abs, err := filepath.Abs(dst)
	if err != nil {
		return fmt.Errorf("определение пути назначения: %w", err)
	}
	if err := os.MkdirAll(abs, 0700); err != nil {
		return fmt.Errorf("создание каталога %s: %w", dst, err)
	}
	root, err := os.OpenRoot(abs)
	if err != nil {
		return fmt.Errorf("открытие каталога назначения %s: %w", dst, err)
	}
	defer root.Close()

	gzr, err := gzip.NewReader(r)
	if err != nil {
		return fmt.Errorf("создание gzip-читателя: %w", err)
	}
	defer func() {
		if err := gzr.Close(); err != nil {
			logrus.Errorf("Ошибка закрытия gzip-потока: %v", err)
		}
	}()

	tr := tar.NewReader(gzr)
	entries := 0
	var expanded int64
	for {
		header, err := tr.Next()
		if errors.Is(err, io.EOF) {
			return nil
		}
		if err != nil {
			return fmt.Errorf("чтение заголовка архива: %w", err)
		}
		if header == nil {
			continue
		}

		entries++
		if entries > maxArchiveEntries {
			return fmt.Errorf("в архиве больше %d записей", maxArchiveEntries)
		}
		if strings.Contains(header.Name, `\`) || !filepath.IsLocal(header.Name) {
			return fmt.Errorf("небезопасный путь в архиве: %q", header.Name)
		}
		name := filepath.Clean(header.Name)
		if name == "." && header.Typeflag != tar.TypeDir {
			return fmt.Errorf("у файла в архиве пустой путь")
		}

		switch header.Typeflag {
		case tar.TypeDir:
			if name != "." {
				if err := root.MkdirAll(name, 0700); err != nil {
					return fmt.Errorf("создание каталога %s: %w", name, err)
				}
			}
		case tar.TypeReg, tar.TypeRegA:
			if header.Size < 0 || header.Size > maxArchiveExpanded-expanded {
				return fmt.Errorf("распакованный архив превышает %d байт", maxArchiveExpanded)
			}
			parent := filepath.Dir(name)
			if parent != "." {
				if err := root.MkdirAll(parent, 0700); err != nil {
					return fmt.Errorf("создание каталога %s: %w", parent, err)
				}
			}
			mode := os.FileMode(0600) | (os.FileMode(header.Mode) & 0100)
			f, err := root.OpenFile(name, os.O_CREATE|os.O_EXCL|os.O_WRONLY, mode)
			if err != nil {
				return fmt.Errorf("создание файла %s: %w", name, err)
			}
			if _, err := io.CopyN(f, tr, header.Size); err != nil {
				_ = f.Close()
				_ = root.Remove(name)
				return fmt.Errorf("копирование файла %s: %w", name, err)
			}
			if err := f.Close(); err != nil {
				_ = root.Remove(name)
				return fmt.Errorf("закрытие файла %s: %w", name, err)
			}
			expanded += header.Size
		default:
			return fmt.Errorf("неподдерживаемый тип записи архива %q", header.Name)
		}
	}
}

func Tar(src string, w io.Writer) error {
	root, err := filepath.Abs(src)
	if err != nil {
		return fmt.Errorf("определение исходного каталога: %w", err)
	}
	if _, err := os.Stat(root); err != nil {
		return fmt.Errorf("исходные данные недоступны: %w", err)
	}

	gzw := gzip.NewWriter(w)
	tw := tar.NewWriter(gzw)
	walkErr := filepath.Walk(root, func(file string, fi os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if fi.Mode()&os.ModeSymlink != 0 || !fi.Mode().IsRegular() && !fi.IsDir() {
			return fmt.Errorf("неподдерживаемый тип исходного файла: %s", file)
		}
		name, err := filepath.Rel(root, file)
		if err != nil {
			return fmt.Errorf("определение пути архива: %w", err)
		}
		header, err := tar.FileInfoHeader(fi, fi.Name())
		if err != nil {
			return fmt.Errorf("создание заголовка архива: %w", err)
		}
		header.Name = name
		if err := tw.WriteHeader(header); err != nil {
			return fmt.Errorf("запись заголовка архива: %w", err)
		}
		if fi.IsDir() {
			return nil
		}
		f, err := os.Open(file)
		if err != nil {
			return fmt.Errorf("открытие файла %s: %w", file, err)
		}
		_, copyErr := io.Copy(tw, f)
		closeErr := f.Close()
		if copyErr != nil {
			return fmt.Errorf("копирование файла %s: %w", file, copyErr)
		}
		if closeErr != nil {
			return fmt.Errorf("закрытие файла %s: %w", file, closeErr)
		}
		return nil
	})
	if closeErr := tw.Close(); walkErr == nil {
		walkErr = closeErr
	}
	if closeErr := gzw.Close(); walkErr == nil {
		walkErr = closeErr
	}
	if walkErr != nil {
		return fmt.Errorf("упаковка каталога %s: %w", src, walkErr)
	}
	return nil
}
