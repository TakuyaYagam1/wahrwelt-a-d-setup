package fs

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
)

func TestOSFSRejectsTraversalAndSymlink(t *testing.T) {
	rootPath := t.TempDir()
	root, err := newOsFs(rootPath)
	require.NoError(t, err)
	t.Cleanup(func() { _ = root.root.Close() })

	_, err = root.Create("../outside")
	require.Error(t, err)

	id := uuid.NewString()
	outside := filepath.Join(t.TempDir(), "outside")
	require.NoError(t, os.WriteFile(outside, []byte("secret"), 0600))
	require.NoError(t, os.Symlink(outside, filepath.Join(rootPath, id)))
	_, err = root.Open(id)
	require.Error(t, err)
	require.Equal(t, "secret", mustRead(t, outside))
}

func mustRead(t *testing.T, name string) string {
	t.Helper()
	data, err := os.ReadFile(name)
	require.NoError(t, err)
	return string(data)
}
