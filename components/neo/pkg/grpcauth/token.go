package grpcauth

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"fmt"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

const authKey = "authorization"

var workerMethods = map[string]struct{}{
	"/exploits.Service/Ping":               {},
	"/exploits.Service/Exploit":            {},
	"/exploits.Service/BroadcastSubscribe": {},
	"/exploits.Service/SingleRunSubscribe": {},
	"/fileserver.Service/DownloadFile":     {},
	"/logs.Service/AddLogLines":            {},
}

const MinKeyLength = 32

func ValidateKey(key string) error {
	if len(key) < MinKeyLength {
		return fmt.Errorf("ключ gRPC должен содержать не менее %d байт", MinKeyLength)
	}
	return nil
}

func ValidateKeyPair(adminKey, workerKey string) error {
	if err := ValidateKey(adminKey); err != nil {
		return fmt.Errorf("админский ключ: %w", err)
	}
	if err := ValidateKey(workerKey); err != nil {
		return fmt.Errorf("ключ worker: %w", err)
	}
	if keyMatches(adminKey, workerKey) {
		return fmt.Errorf("админский ключ и ключ worker должны различаться")
	}
	return nil
}

func keyMatches(expected, provided string) bool {
	expectedHash := sha256.Sum256([]byte(expected))
	providedHash := sha256.Sum256([]byte(provided))
	return subtle.ConstantTimeCompare(expectedHash[:], providedHash[:]) == 1
}

func NewServerInterceptor(key string) *ServerTokenInterceptor {
	return &ServerTokenInterceptor{
		adminKey: key,
	}
}

func NewServerInterceptorWithWorkerKey(adminKey, workerKey string) *ServerTokenInterceptor {
	return &ServerTokenInterceptor{adminKey: adminKey, workerKey: workerKey}
}

type ServerTokenInterceptor struct {
	adminKey  string
	workerKey string
}

func (ti *ServerTokenInterceptor) authorize(ctx context.Context, method string) error {
	md, ok := metadata.FromIncomingContext(ctx)
	if !ok {
		return status.Errorf(codes.Unauthenticated, "метаданные не переданы")
	}
	values := md[authKey]
	if len(values) != 1 {
		return status.Errorf(codes.Unauthenticated, "токен авторизации не передан")
	}
	adminMatch := keyMatches(ti.adminKey, values[0])
	workerMatch := keyMatches(ti.workerKey, values[0])
	if adminMatch {
		return nil
	}
	if !workerMatch {
		return status.Errorf(codes.Unauthenticated, "передан неверный токен")
	}
	if _, allowed := workerMethods[method]; !allowed {
		return status.Errorf(codes.PermissionDenied, "токен worker не разрешает этот вызов")
	}
	return nil
}

func (ti *ServerTokenInterceptor) Unary() grpc.UnaryServerInterceptor {
	return func(
		ctx context.Context,
		req interface{},
		info *grpc.UnaryServerInfo,
		handler grpc.UnaryHandler,
	) (interface{}, error) {
		if err := ti.authorize(ctx, info.FullMethod); err != nil {
			return nil, err
		}
		return handler(ctx, req)
	}
}

func (ti *ServerTokenInterceptor) Stream() grpc.StreamServerInterceptor {
	return func(
		srv interface{},
		stream grpc.ServerStream,
		info *grpc.StreamServerInfo,
		handler grpc.StreamHandler,
	) error {
		if err := ti.authorize(stream.Context(), info.FullMethod); err != nil {
			return err
		}
		return handler(srv, stream)
	}
}

func NewClientInterceptor(key string) *ClientTokenInterceptor {
	return &ClientTokenInterceptor{token: key}
}

type ClientTokenInterceptor struct {
	token string
}

func (interceptor *ClientTokenInterceptor) attachToken(ctx context.Context) context.Context {
	return metadata.AppendToOutgoingContext(ctx, authKey, interceptor.token)
}

func (interceptor *ClientTokenInterceptor) Unary() grpc.UnaryClientInterceptor {
	return func(
		ctx context.Context,
		method string,
		req, reply interface{},
		cc *grpc.ClientConn,
		invoker grpc.UnaryInvoker,
		opts ...grpc.CallOption,
	) error {
		return invoker(interceptor.attachToken(ctx), method, req, reply, cc, opts...)
	}
}

func (interceptor *ClientTokenInterceptor) Stream() grpc.StreamClientInterceptor {
	return func(
		ctx context.Context,
		desc *grpc.StreamDesc,
		cc *grpc.ClientConn,
		method string,
		streamer grpc.Streamer,
		opts ...grpc.CallOption,
	) (grpc.ClientStream, error) {
		return streamer(interceptor.attachToken(ctx), desc, cc, method, opts...)
	}
}
