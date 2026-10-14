//go:build linux

package client

import (
	"testing"

	"github.com/stretchr/testify/require"
	"golang.org/x/sys/unix"
)

func TestHardenProcessDisablesDumpability(t *testing.T) {
	require.NoError(t, HardenProcess())
	dumpable, err := unix.PrctlRetInt(unix.PR_GET_DUMPABLE, 0, 0, 0, 0)
	require.NoError(t, err)
	require.Zero(t, dumpable)
}
