package client

import (
	"context"
	"errors"
	"fmt"
	"io"

	"github.com/sirupsen/logrus"
	"google.golang.org/grpc"

	"github.com/c4t-but-s4d/neo/v2/pkg/filestream"
	epb "github.com/c4t-but-s4d/neo/v2/proto/go/exploits"
	fspb "github.com/c4t-but-s4d/neo/v2/proto/go/fileserver"
	logspb "github.com/c4t-but-s4d/neo/v2/proto/go/logs"
)

func New(cc grpc.ClientConnInterface, id string) *Client {
	return &Client{
		exploits: epb.NewServiceClient(cc),
		fs:       fspb.NewServiceClient(cc),
		logs:     logspb.NewServiceClient(cc),
		ID:       id,
	}
}

type Client struct {
	exploits epb.ServiceClient
	fs       fspb.ServiceClient
	logs     logspb.ServiceClient

	ID     string
	Weight int
}

func (nc *Client) GetServerState(ctx context.Context) (*epb.ServerState, error) {
	resp, err := nc.exploits.Ping(
		ctx,
		&epb.PingRequest{
			ClientId: nc.ID,
			Payload: &epb.PingRequest_ServerInfoRequest{
				ServerInfoRequest: &epb.PingRequest_ServerInfo{},
			},
		},
	)
	if err != nil {
		return nil, fmt.Errorf("запрос проверки связи: %w", err)
	}
	return resp.State, nil
}

func (nc *Client) Heartbeat(ctx context.Context) (*epb.ServerState, error) {
	resp, err := nc.exploits.Ping(
		ctx,
		&epb.PingRequest{
			ClientId: nc.ID,
			Payload: &epb.PingRequest_HeartbeatRequest{
				HeartbeatRequest: &epb.PingRequest_Heartbeat{
					Weight: int32(nc.Weight),
				},
			},
		},
	)
	if err != nil {
		return nil, fmt.Errorf("запрос проверки связи: %w", err)
	}
	return resp.State, nil
}

func (nc *Client) Leave(ctx context.Context) error {
	if _, err := nc.exploits.Ping(
		ctx,
		&epb.PingRequest{
			ClientId: nc.ID,
			Payload: &epb.PingRequest_LeaveRequest{
				LeaveRequest: &epb.PingRequest_Leave{},
			},
		},
	); err != nil {
		return fmt.Errorf("запрос проверки связи: %w", err)
	}
	return nil
}

func (nc *Client) Exploit(ctx context.Context, id string) (*epb.ExploitResponse, error) {
	req := &epb.ExploitRequest{
		ExploitId: id,
	}
	resp, err := nc.exploits.Exploit(ctx, req)
	if err != nil {
		return nil, fmt.Errorf("запрос эксплойта: %w", err)
	}
	return resp, nil
}

func (nc *Client) UpdateExploit(ctx context.Context, state *epb.ExploitState) (*epb.ExploitState, error) {
	req := &epb.UpdateExploitRequest{State: state}
	resp, err := nc.exploits.UpdateExploit(ctx, req)
	if err != nil {
		return nil, fmt.Errorf("запрос обновления эксплойта: %w", err)
	}
	return resp.State, nil
}

func (nc *Client) DownloadFile(ctx context.Context, info *fspb.FileInfo, out io.Writer) error {
	resp, err := nc.fs.DownloadFile(ctx, info)
	if err != nil {
		return fmt.Errorf("запрос скачивания файла: %w", err)
	}
	if err := filestream.SaveLimit(resp, out, filestream.MaxFileSize); err != nil {
		return fmt.Errorf("сохранение скачанного файла: %w", err)
	}
	if err := resp.CloseSend(); err != nil {
		return fmt.Errorf("закрытие потока: %w", err)
	}
	return nil
}

func (nc *Client) UploadFile(ctx context.Context, r io.Reader) (*fspb.FileInfo, error) {
	client, err := nc.fs.UploadFile(ctx)
	if err != nil {
		return nil, fmt.Errorf("запрос загрузки файла: %w", err)
	}
	if err := filestream.Load(r, client); err != nil {
		return nil, fmt.Errorf("передача файла в поток: %w", err)
	}
	fileInfo, err := client.CloseAndRecv()
	if err != nil {
		return nil, fmt.Errorf("закрытие потока и чтение ответа о загрузке: %w", err)
	}
	return fileInfo, nil
}

func (nc *Client) BroadcastCommand(ctx context.Context, command string) error {
	req := &epb.BroadcastRequest{Command: command}
	if _, err := nc.exploits.BroadcastCommand(ctx, req); err != nil {
		return fmt.Errorf("запрос рассылки команды: %w", err)
	}
	return nil
}

func (nc *Client) SingleRun(ctx context.Context, exploitID string) error {
	req := &epb.SingleRunRequest{ExploitId: exploitID}
	if _, err := nc.exploits.SingleRun(ctx, req); err != nil {
		return fmt.Errorf("запрос одиночного запуска: %w", err)
	}
	return nil
}

func (nc *Client) SetExploitDisabled(ctx context.Context, id string, disabled bool) error {
	resp, err := nc.Exploit(ctx, id)
	if err != nil {
		return fmt.Errorf("получение текущей конфигурации эксплойта: %w", err)
	}

	req := &epb.UpdateExploitRequest{State: resp.State}
	req.State.Config.Disabled = disabled

	if _, err := nc.exploits.UpdateExploit(ctx, req); err != nil {
		return fmt.Errorf("запрос изменения состояния эксплойта: %w", err)
	}
	return nil
}

func (nc *Client) ListenBroadcasts(ctx context.Context) (<-chan *epb.BroadcastSubscribeResponse, error) {
	stream, err := nc.exploits.BroadcastSubscribe(ctx, &epb.BroadcastSubscribeRequest{})
	if err != nil {
		return nil, fmt.Errorf("создание потока запросов рассылки: %w", err)
	}

	results := make(chan *epb.BroadcastSubscribeResponse)
	go func() {
		defer close(results)
		for {
			cmd, err := stream.Recv()
			if !checkStreamError("broadcast", err, stream.Context().Err()) {
				return
			}
			select {
			case results <- cmd:
			case <-ctx.Done():
				logrus.Debugf("Контекст рассылки отменен")
				return
			}
		}
	}()

	return results, nil
}

func (nc *Client) ListenSingleRuns(ctx context.Context) (<-chan *epb.SingleRunSubscribeResponse, error) {
	stream, err := nc.exploits.SingleRunSubscribe(ctx, &epb.SingleRunSubscribeRequest{})
	if err != nil {
		return nil, fmt.Errorf("создание потока одиночных запусков: %w", err)
	}

	results := make(chan *epb.SingleRunSubscribeResponse)
	go func() {
		defer close(results)
		for {
			er, err := stream.Recv()
			if !checkStreamError("single runs", err, stream.Context().Err()) {
				return
			}
			select {
			case results <- er:
			case <-ctx.Done():
				logrus.Warningf("Контекст одиночного запуска отменен")
				return
			}
		}
	}()

	return results, nil
}

func (nc *Client) AddLogLines(ctx context.Context, lines ...*logspb.LogLine) error {
	req := logspb.AddLogLinesRequest{Lines: lines}
	if _, err := nc.logs.AddLogLines(ctx, &req); err != nil {
		return fmt.Errorf("отправка пакета строк журнала (%d): %w", len(lines), err)
	}
	return nil
}

func (nc *Client) SearchLogLines(ctx context.Context, exploit string, version int64) (<-chan []*logspb.LogLine, error) {
	req := logspb.SearchLogLinesRequest{
		Exploit: exploit,
		Version: version,
	}
	stream, err := nc.logs.SearchLogLines(ctx, &req)
	if err != nil {
		return nil, fmt.Errorf("запрос к серверу: %w", err)
	}

	results := make(chan []*logspb.LogLine)
	go func() {
		defer close(results)
		for {
			resp, err := stream.Recv()
			if err != nil {
				if !errors.Is(err, io.EOF) {
					logrus.Errorf("Ошибка чтения строк журнала: %v", err)
				}
				return
			}
			select {
			case results <- resp.Lines:
			case <-ctx.Done():
				logrus.Debugf("Контекст чтения журнала отменен")
				return
			}
		}
	}()

	return results, nil
}
