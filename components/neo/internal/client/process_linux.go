//go:build linux

package client

import "golang.org/x/sys/unix"

// HardenProcess не дает заданиям с тем же UID читать память и окружение
// worker через /proc, сохраняя возможность запускать дочерние процессы.
func HardenProcess() error {
	return unix.Prctl(unix.PR_SET_DUMPABLE, 0, 0, 0, 0)
}
