package cli

import (
	"context"
	"fmt"
	"time"

	"github.com/samber/lo"
	"github.com/sirupsen/logrus"
	"github.com/spf13/cobra"
	"google.golang.org/protobuf/types/known/durationpb"

	"github.com/c4t-but-s4d/neo/v2/internal/client"

	epb "github.com/c4t-but-s4d/neo/v2/proto/go/exploits"
)

type updateCLI struct {
	*baseCLI
	exploitID string
	runEvery  *time.Duration
	timeout   *time.Duration
	endless   *bool
	disabled  *bool
}

func NewUpdateCLI(cmd *cobra.Command, args []string, cfg *client.Config) NeoCLI {
	c := &updateCLI{
		baseCLI:   &baseCLI{cfg: cfg},
		exploitID: args[0],
	}

	if cmd.Flags().Changed("interval") {
		runEvery, err := cmd.Flags().GetDuration("interval")
		if err != nil {
			logrus.Fatalf("Не удалось прочитать интервал запуска: %v", err)
		}
		c.runEvery = &runEvery
	}
	if cmd.Flags().Changed("timeout") {
		timeout, err := cmd.Flags().GetDuration("timeout")
		if err != nil {
			logrus.Fatalf("Не удалось прочитать тайм-аут запуска: %v", err)
		}
		c.timeout = &timeout
	}
	if cmd.Flags().Changed("endless") {
		endless, err := cmd.Flags().GetBool("endless")
		if err != nil {
			logrus.Fatalf("Не удалось прочитать параметр бесконечного режима: %v", err)
		}
		c.endless = &endless
	}
	if cmd.Flags().Changed("disabled") {
		disabled, err := cmd.Flags().GetBool("disabled")
		if err != nil {
			logrus.Fatalf("Не удалось прочитать параметр отключения: %v", err)
		}
		c.disabled = &disabled
	}

	return c
}

func (uc *updateCLI) Run(ctx context.Context) error {
	logrus.Infof("Изменение настроек эксплойта с id = %s", uc.exploitID)

	c, err := uc.client()
	if err != nil {
		return fmt.Errorf("не удалось создать клиент: %w", err)
	}

	resp, err := c.Exploit(ctx, uc.exploitID)
	if err != nil {
		logrus.Fatalf("Эксплойт с id = %s не найден", uc.exploitID)
	}
	es := resp.GetState()
	escfg := es.GetConfig()

	runEvery := escfg.GetRunEvery()
	if uc.runEvery != nil {
		runEvery = durationpb.New(*uc.runEvery)
	}
	timeout := escfg.GetTimeout()
	if uc.timeout != nil {
		timeout = durationpb.New(*uc.timeout)
	}

	newState := &epb.ExploitState{
		ExploitId: es.GetExploitId(),
		File:      es.GetFile(),
		Version:   es.GetVersion(),
		Config: &epb.ExploitConfiguration{
			Entrypoint: escfg.GetEntrypoint(),
			IsArchive:  escfg.GetIsArchive(),
			RunEvery:   runEvery,
			Timeout:    timeout,
			Endless:    lo.FromPtrOr(uc.endless, escfg.GetEndless()),
			Disabled:   lo.FromPtrOr(uc.disabled, escfg.GetDisabled()),
		},
	}

	ns, err := c.UpdateExploit(ctx, newState)
	if err != nil {
		return fmt.Errorf("не удалось обновить эксплойт: %w", err)
	}
	logrus.Infof("Настройки эксплойта %s обновлены", ns.GetExploitId())
	return nil
}
