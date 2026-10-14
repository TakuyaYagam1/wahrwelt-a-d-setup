package main

import (
	"context"
	"log"
	"os"
	"os/signal"

	"github.com/c4t-but-s4d/neo/v2/cmd/client/cmd"
	"github.com/c4t-but-s4d/neo/v2/internal/client"
)

func main() {
	if err := client.HardenProcess(); err != nil {
		log.Fatalf("Не удалось ограничить чтение памяти worker через /proc: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan interface{}, 1)
	go func() {
		cmd.Execute(ctx)
		done <- nil
	}()
	c := make(chan os.Signal, 1)
	signal.Notify(c, os.Interrupt)

	select {
	case <-c:
		cancel()
		<-done
	case <-done:
	}
}
