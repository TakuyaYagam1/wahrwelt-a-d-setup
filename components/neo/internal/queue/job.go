package queue

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"syscall"
	"time"

	"github.com/c4t-but-s4d/neo/v2/internal/models"
	"github.com/c4t-but-s4d/neo/v2/pkg/joblogger"
)

func NewJob(
	exploit *models.Exploit,
	target *models.Target,
	executable, dir string,
	environ []string,
	timeout time.Duration,
	logger *joblogger.JobLogger,
) *Job {
	return &Job{
		Exploit: exploit,
		Target:  target,

		executable: executable,
		dir:        dir,
		timeout:    timeout,
		environ:    environ,
		logger:     logger,
	}
}

type Job struct {
	Exploit *models.Exploit
	Target  *models.Target

	executable string
	dir        string
	environ    []string
	timeout    time.Duration
	logger     *joblogger.JobLogger
}

func (t *Job) String() string {
	return fmt.Sprintf(
		"Job(exploit=%v, target=%v, path=%s, timeout=%v, env-vars=%d)",
		t.Exploit,
		t.Target,
		t.executable,
		t.timeout,
		len(t.environ),
	)
}

func (t *Job) Command(ctx context.Context) *exec.Cmd {
	cmd := exec.CommandContext(ctx, t.executable, t.Target.IP)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	cmd.Cancel = func() error {
		if cmd.Process == nil {
			return os.ErrProcessDone
		}
		if err := syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL); err != nil && err != syscall.ESRCH {
			return err
		}
		return nil
	}
	cmd.WaitDelay = 2 * time.Second
	if t.dir != "" {
		cmd.Dir = t.dir
	}

	cmd.Env = jobEnvironment(os.Environ(), t.environ)

	// Отключить буферизацию вывода скриптов Python.
	cmd.Env = append(cmd.Env, "PYTHONUNBUFFERED=1")
	// Отключить использование терминала в pwntools.
	cmd.Env = append(cmd.Env, "PWNLIB_NOTERM=1")
	return cmd
}

var controlPlaneSecrets = map[string]struct{}{
	"NEO_GRPC_AUTH_KEY": {},
	"NEO_WORKER_KEY":    {},
	"NEO_KEY":           {},
	"GRPC_AUTH_KEY":     {},
	"FARM_PASSWORD":     {},
	"NEO_FARM_PASSWORD": {},
}

func jobEnvironment(base, extra []string) []string {
	values := make([]string, 0, len(base)+len(extra))
	positions := make(map[string]int, len(base)+len(extra))
	for _, entry := range append(append([]string(nil), base...), extra...) {
		key, _, ok := strings.Cut(entry, "=")
		if !ok {
			continue
		}
		if _, secret := controlPlaneSecrets[strings.ToUpper(key)]; secret {
			continue
		}
		if pos, exists := positions[key]; exists {
			values[pos] = entry
			continue
		}
		positions[key] = len(values)
		values = append(values, entry)
	}
	return values
}
