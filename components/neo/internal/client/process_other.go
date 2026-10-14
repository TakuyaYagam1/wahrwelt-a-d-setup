//go:build !linux

package client

func HardenProcess() error { return nil }
