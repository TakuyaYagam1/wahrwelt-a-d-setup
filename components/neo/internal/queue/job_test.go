package queue

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/c4t-but-s4d/neo/v2/internal/models"
	"github.com/stretchr/testify/require"
)

func TestJobEnvironmentDoesNotPassControlPlaneSecrets(t *testing.T) {
	env := jobEnvironment(
		[]string{"PATH=/usr/bin", "NEO_GRPC_AUTH_KEY=worker-secret", "NEO_FARM_PASSWORD=farm-secret"},
		[]string{"NEO_WORKER_KEY=worker-secret", "TEAM_TOKEN=job-token", "MODE=old", "MODE=new"},
	)
	require.Equal(t, []string{"PATH=/usr/bin", "TEAM_TOKEN=job-token", "MODE=new"}, env)
}

func TestJobStringDoesNotIncludeEnvironmentValues(t *testing.T) {
	job := NewJob(nil, &models.Target{IP: "127.0.0.1"}, "/bin/true", "", []string{"NEO_GRPC_AUTH_KEY=worker-secret"}, time.Second, nil)
	require.NotContains(t, job.String(), "worker-secret")
	require.NotContains(t, job.Command(context.Background()).Env, "NEO_GRPC_AUTH_KEY=worker-secret")
}

func TestJobTimeoutKillsChildProcesses(t *testing.T) {
	dir := t.TempDir()
	marker := filepath.Join(dir, "child-finished")
	script := filepath.Join(dir, "job.sh")
	contents := "#!/bin/sh\n(sleep 0.5; printf done > \"" + marker + "\") &\nwait\n"
	require.NoError(t, os.WriteFile(script, []byte(contents), 0700))

	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	job := NewJob(nil, &models.Target{IP: "127.0.0.1"}, script, dir, nil, time.Second, nil)
	started := time.Now()
	err := job.Command(ctx).Run()
	require.Error(t, err)
	require.Less(t, time.Since(started), time.Second)
	time.Sleep(600 * time.Millisecond)
	_, err = os.Stat(marker)
	require.ErrorIs(t, err, os.ErrNotExist)
}
