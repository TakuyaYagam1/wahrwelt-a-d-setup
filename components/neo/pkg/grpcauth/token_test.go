package grpcauth

import (
	"context"
	"testing"

	epb "github.com/c4t-but-s4d/neo/v2/proto/go/exploits"
	fspb "github.com/c4t-but-s4d/neo/v2/proto/go/fileserver"
	lpb "github.com/c4t-but-s4d/neo/v2/proto/go/logs"
	"github.com/stretchr/testify/require"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

const (
	testAdminKey  = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	testWorkerKey = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
)

func authContext(key string) context.Context {
	return metadata.NewIncomingContext(context.Background(), metadata.Pairs(authKey, key))
}

func TestWorkerTokenMethodAccess(t *testing.T) {
	interceptor := NewServerInterceptorWithWorkerKey(testAdminKey, testWorkerKey)
	for method := range workerMethods {
		require.NoError(t, interceptor.authorize(authContext(testWorkerKey), method), method)
	}
}

func TestWorkerTokenCannotCallAdministrativeOrUnknownMethods(t *testing.T) {
	interceptor := NewServerInterceptorWithWorkerKey(testAdminKey, testWorkerKey)
	services := []struct {
		name    string
		methods []string
		streams []string
	}{
		{epb.Service_ServiceDesc.ServiceName, methodNames(epb.Service_ServiceDesc.Methods), streamNames(epb.Service_ServiceDesc.Streams)},
		{fspb.Service_ServiceDesc.ServiceName, methodNames(fspb.Service_ServiceDesc.Methods), streamNames(fspb.Service_ServiceDesc.Streams)},
		{lpb.Service_ServiceDesc.ServiceName, methodNames(lpb.Service_ServiceDesc.Methods), streamNames(lpb.Service_ServiceDesc.Streams)},
	}
	for _, service := range services {
		for _, method := range append(service.methods, service.streams...) {
			fullMethod := "/" + service.name + "/" + method
			_, workerAllowed := workerMethods[fullMethod]
			err := interceptor.authorize(authContext(testWorkerKey), fullMethod)
			if workerAllowed {
				require.NoError(t, err, fullMethod)
			} else {
				require.Equal(t, codes.PermissionDenied, status.Code(err), fullMethod)
			}
		}
	}
	err := interceptor.authorize(authContext(testWorkerKey), "/unknown.Service/Method")
	require.Equal(t, codes.PermissionDenied, status.Code(err))
}

func methodNames(methods []grpc.MethodDesc) []string {
	result := make([]string, 0, len(methods))
	for _, method := range methods {
		result = append(result, method.MethodName)
	}
	return result
}

func streamNames(streams []grpc.StreamDesc) []string {
	result := make([]string, 0, len(streams))
	for _, stream := range streams {
		result = append(result, stream.StreamName)
	}
	return result
}

func TestAdminTokenCanCallAllMethods(t *testing.T) {
	interceptor := NewServerInterceptorWithWorkerKey(testAdminKey, testWorkerKey)
	require.NoError(t, interceptor.authorize(authContext(testAdminKey), "/unknown.Service/Method"))
}

func TestAuthorizationComparisonRejectsMismatchesAtEveryPosition(t *testing.T) {
	interceptor := NewServerInterceptorWithWorkerKey(testAdminKey, testWorkerKey)
	for i := range testAdminKey {
		wrong := []byte(testAdminKey)
		wrong[i] ^= 1
		err := interceptor.authorize(authContext(string(wrong)), "/exploits.Service/Ping")
		require.Equal(t, codes.Unauthenticated, status.Code(err))
	}
}

func TestValidateKeyPair(t *testing.T) {
	require.NoError(t, ValidateKeyPair(testAdminKey, testWorkerKey))
	require.Error(t, ValidateKeyPair(testAdminKey, testAdminKey))
	require.Error(t, ValidateKeyPair("short", testWorkerKey))
	require.Error(t, ValidateKeyPair(testAdminKey, "short"))
}
