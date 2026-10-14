package filestream

import (
	"errors"
	"fmt"
	"io"

	fspb "github.com/c4t-but-s4d/neo/v2/proto/go/fileserver"
)

const (
	chunkSize = 2 * 1024 * 1024 // 2MB
	// MaxFileSize ограничивает размер файлов, передаваемых по gRPC.
	MaxFileSize int64 = 128 << 20
)

type DownloadStream interface {
	Recv() (*fspb.FileStream, error)
}

type UploadStream interface {
	Send(*fspb.FileStream) error
}

func Save(stream DownloadStream, out io.Writer) error {
	return SaveLimit(stream, out, 0)
}

// SaveLimit копирует поток с ограничением размера. Нулевой лимит означает,
// что размер не ограничен; так сохраняется совместимость с вызывающим кодом,
// который устанавливает собственный предел.
func SaveLimit(stream DownloadStream, out io.Writer, limit int64) error {
	var total int64
	for {
		in, err := stream.Recv()
		if errors.Is(err, io.EOF) {
			return nil
		}
		if err != nil {
			return fmt.Errorf("чтение из потока: %w", err)
		}
		if limit > 0 && (int64(len(in.Chunk)) > limit-total) {
			return fmt.Errorf("размер файла превышает лимит %d байт", limit)
		}
		n, err := out.Write(in.Chunk)
		if err != nil {
			return fmt.Errorf("запись части потока: %w", err)
		}
		if n != len(in.Chunk) {
			return io.ErrShortWrite
		}
		total += int64(n)
	}
}

func Load(in io.Reader, stream UploadStream) error {
	b := make([]byte, chunkSize)
	for {
		n, err := in.Read(b)
		if errors.Is(err, io.EOF) {
			return nil
		}
		if err != nil {
			return fmt.Errorf("чтение содержимого: %w", err)
		}
		if err := stream.Send(&fspb.FileStream{Chunk: b[:n]}); err != nil {
			return fmt.Errorf("отправка части в поток: %w", err)
		}
	}
}
