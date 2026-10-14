package fs

import (
	"fmt"

	"github.com/google/uuid"
	"github.com/sirupsen/logrus"
	"google.golang.org/grpc/codes"

	"github.com/c4t-but-s4d/neo/v2/internal/server/common"
	serverConfig "github.com/c4t-but-s4d/neo/v2/internal/server/config"
	"github.com/c4t-but-s4d/neo/v2/internal/server/utils"
	"github.com/c4t-but-s4d/neo/v2/pkg/filestream"
	fspb "github.com/c4t-but-s4d/neo/v2/proto/go/fileserver"
)

func New(cfg *serverConfig.Config) (*Server, error) {
	fs, err := newOsFs(cfg.BaseDir)
	if err != nil {
		return nil, fmt.Errorf("создание файлового хранилища: %w", err)
	}
	ems := &Server{
		LoggingServer: common.NewLoggingServer("fileserver"),

		fs: fs,
	}
	return ems, nil
}

type Server struct {
	fspb.UnimplementedServiceServer
	common.LoggingServer

	fs filesystem
}

func (s *Server) UploadFile(stream fspb.Service_UploadFileServer) (retErr error) {
	info := &fspb.FileInfo{Uuid: uuid.NewString()}
	s.GetMethodLogger(stream.Context()).Infof("New file upload: %v", info)

	of, err := s.fs.Create(info.Uuid)
	if err != nil {
		return utils.WrapErrorf(codes.Internal, "Не удалось создать файл: %v", err)
	}
	defer func() {
		if cerr := of.Close(); cerr != nil {
			if retErr == nil {
				retErr = utils.WrapErrorf(codes.Internal, "Не удалось закрыть файл назначения")
			}
		}
		if retErr != nil {
			if rerr := s.fs.Remove(info.Uuid); rerr != nil {
				logrus.Errorf("Не удалось удалить незавершенную загрузку: %v", rerr)
			}
		}
	}()

	if err := filestream.SaveLimit(stream, of, filestream.MaxFileSize); err != nil {
		return utils.WrapErrorf(codes.ResourceExhausted, "Не удалось принять файл из потока: %v", err)
	}
	if err := stream.SendAndClose(info); err != nil {
		return utils.WrapErrorf(codes.Internal, "Не удалось отправить ответ и закрыть соединение: %v", err)
	}
	return nil
}

func (s *Server) DownloadFile(fi *fspb.FileInfo, stream fspb.Service_DownloadFileServer) error {
	s.LogRequest(stream.Context(), fi)
	if fi == nil {
		return utils.WrapErrorf(codes.InvalidArgument, "Требуется идентификатор файла")
	}

	f, err := s.fs.Open(fi.Uuid)
	if err != nil {
		return utils.WrapErrorf(codes.NotFound, "Не удалось найти файл с uuid %s: %v", fi.Uuid, err)
	}
	defer func() {
		if err := f.Close(); err != nil {
			logrus.Errorf("Не удалось закрыть скачанный файл: %v", err)
		}
	}()
	if err := filestream.Load(f, stream); err != nil {
		return utils.WrapErrorf(codes.NotFound, "Не удалось найти файл с uuid %s: %v", fi.Uuid, err)
	}
	return nil
}
