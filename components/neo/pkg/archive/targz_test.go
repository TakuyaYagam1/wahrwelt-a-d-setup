package archive

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"fmt"
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/require"
)

func testTarGz(t *testing.T, headers []tar.Header, bodies [][]byte) *bytes.Buffer {
	t.Helper()
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	for i := range headers {
		require.NoError(t, tw.WriteHeader(&headers[i]))
		if headers[i].Typeflag == tar.TypeReg || headers[i].Typeflag == tar.TypeRegA {
			if len(bodies[i]) > 0 {
				_, err := tw.Write(bodies[i])
				require.NoError(t, err)
			}
		}
	}
	require.NoError(t, tw.Close())
	require.NoError(t, gz.Close())
	return &buf
}

func TestUntarRejectsEscapingPathsAndLinks(t *testing.T) {
	for _, header := range []tar.Header{
		{Name: "../outside", Typeflag: tar.TypeReg, Mode: 0600, Size: 1},
		{Name: "/tmp/outside", Typeflag: tar.TypeReg, Mode: 0600, Size: 1},
		{Name: "link", Typeflag: tar.TypeSymlink, Linkname: "../outside", Mode: 0777},
		{Name: "hardlink", Typeflag: tar.TypeLink, Linkname: "../outside", Mode: 0600},
	} {
		t.Run(header.Name, func(t *testing.T) {
			dst := t.TempDir()
			archive := testTarGz(t, []tar.Header{header}, [][]byte{{'x'}})
			require.Error(t, Untar(dst, archive))
			require.NoError(t, filepath.WalkDir(dst, func(_ string, d os.DirEntry, err error) error {
				if err != nil {
					return err
				}
				if d.Type()&os.ModeSymlink != 0 {
					t.Errorf("archive created a symlink: %s", d.Name())
				}
				return nil
			}))
		})
	}
}

func TestUntarRejectsExpandedSizeLimit(t *testing.T) {
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	require.NoError(t, tw.WriteHeader(&tar.Header{
		Name: "large", Typeflag: tar.TypeReg, Mode: 0600, Size: maxArchiveExpanded + 1,
	}))
	require.NoError(t, gz.Close())
	require.ErrorContains(t, Untar(t.TempDir(), &buf), "превышает")
}

func TestUntarPreservesOnlyOwnerExecute(t *testing.T) {
	dst := t.TempDir()
	archive := testTarGz(t, []tar.Header{
		{Name: "main", Typeflag: tar.TypeReg, Mode: 0755, Size: 1},
		{Name: "helper", Typeflag: tar.TypeReg, Mode: 0755, Size: 1},
	}, [][]byte{{'m'}, {'h'}})
	require.NoError(t, Untar(dst, archive))
	for _, name := range []string{"main", "helper"} {
		info, err := os.Stat(filepath.Join(dst, name))
		require.NoError(t, err)
		require.Equal(t, os.FileMode(0700), info.Mode().Perm())
	}
}

func TestUntarRejectsTooManyEntries(t *testing.T) {
	headers := make([]tar.Header, maxArchiveEntries+1)
	bodies := make([][]byte, len(headers))
	for i := range headers {
		headers[i] = tar.Header{Name: fmt.Sprintf("entry/%d", i), Typeflag: tar.TypeDir, Mode: 0700}
	}
	archive := testTarGz(t, headers, bodies)
	require.ErrorContains(t, Untar(t.TempDir(), archive), "больше")
}
