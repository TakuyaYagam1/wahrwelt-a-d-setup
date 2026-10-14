package cli

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"

	"github.com/denisbrodbeck/machineid"
	"github.com/sirupsen/logrus"
	"github.com/spf13/viper"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/encoding/gzip"

	"github.com/c4t-but-s4d/neo/v2/internal/client"
	"github.com/c4t-but-s4d/neo/v2/pkg/grpcauth"
)

type NeoCLI interface {
	Run(ctx context.Context) error
}

type baseCLI struct {
	cfg *client.Config

	clientID string
}

func (cmd *baseCLI) client() (*client.Client, error) {
	if err := grpcauth.ValidateKey(cmd.cfg.GrpcAuthKey); err != nil {
		return nil, fmt.Errorf("неверный ключ gRPC: %w", err)
	}
	opts := []grpc.DialOption{
		grpc.WithDefaultCallOptions(
			grpc.UseCompressor(gzip.Name),
		),
	}
	interceptor := grpcauth.NewClientInterceptor(cmd.cfg.GrpcAuthKey)
	opts = append(
		opts,
		grpc.WithUnaryInterceptor(interceptor.Unary()),
		grpc.WithStreamInterceptor(interceptor.Stream()),
	)
	if !cmd.cfg.UseTLS {
		opts = append(
			opts,
			grpc.WithTransportCredentials(insecure.NewCredentials()),
		)
	} else {
		opts = append(opts, grpc.WithTransportCredentials(credentials.NewTLS(&tls.Config{})))
	}
	conn, err := grpc.Dial(cmd.cfg.Host, opts...)
	if err != nil {
		return nil, fmt.Errorf("не удалось подключиться к gRPC-серверу: %w", err)
	}
	return client.New(conn, cmd.ClientID()), nil
}

func (cmd *baseCLI) ClientID() string {
	if cmd.clientID != "" {
		return cmd.clientID
	}

	cmd.clientID = viper.GetString("client_id")
	if cmd.clientID == "" {
		var err error
		if cmd.clientID, err = machineid.ID(); err != nil {
			logrus.Fatalf("Не удалось получить уникальный идентификатор клиента: %v", err)
		}
	}
	logrus.Infof("Идентификатор клиента: %s", cmd.clientID)
	return cmd.clientID
}

func (cmd *baseCLI) Run(_ context.Context) error {
	return errors.New("команда не реализована")
}
