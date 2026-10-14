package queue

import (
	"context"
	"errors"
	"fmt"
	"os/exec"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/sirupsen/logrus"
)

const (
	jobBufferSize    = 1000
	resultBufferSize = 1000
)

// Проверки типов во время компиляции.
var (
	_ Queue = (*simpleQueue)(nil)
)

type simpleQueue struct {
	out     chan *Output
	c       chan *Job
	maxJobs int
	metrics *Metrics
	logger  *logrus.Entry
}

func NewSimpleQueue(maxJobs int) Queue {
	id := uuid.NewString()[:8]

	return &simpleQueue{
		out:     make(chan *Output, resultBufferSize),
		c:       make(chan *Job, jobBufferSize),
		maxJobs: maxJobs,
		metrics: NewMetrics("neo", id, TypeSimple),
		logger: logrus.WithFields(logrus.Fields{
			"component": "simple_queue",
			"id":        id,
		}),
	}
}

func (q *simpleQueue) Type() Type {
	return TypeSimple
}

func (q *simpleQueue) Size() int {
	return len(q.c)
}

// Start выполняется синхронно. Чтобы остановить очередь, отмените контекст.
func (q *simpleQueue) Start(ctx context.Context) {
	q.logger.WithField("jobs", q.maxJobs).Info("Запуск очереди")

	q.metrics.MaxJobs.Add(float64(q.maxJobs))
	defer q.metrics.MaxJobs.Sub(float64(q.maxJobs))

	wg := sync.WaitGroup{}
	wg.Add(q.maxJobs)
	for i := 0; i < q.maxJobs; i++ {
		go func() {
			defer wg.Done()
			q.worker(ctx)
		}()
	}
	wg.Wait()

	q.logger.Info("Очередь остановлена")
}

func (q *simpleQueue) Results() <-chan *Output {
	return q.out
}

func (q *simpleQueue) Add(task *Job) error {
	select {
	case q.c <- task:
		return nil
	default:
		return ErrQueueFull
	}
}

func (q *simpleQueue) String() string {
	return "SimpleQueue"
}

func (q *simpleQueue) worker(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case task := <-q.c:
			res, err := q.runExploit(ctx, task)

			exploitLabels := task.Exploit.MetricLabels()

			var exitErr *exec.ExitError
			switch {
			case err == nil:
				task.logger.Infof("Задание выполнено")
				q.metrics.ExploitsFinished.With(exploitLabels).Inc()
			case errors.Is(err, context.Canceled):
				// При перезапуске очереди ожидается ошибка.
				q.metrics.ExploitsFinished.With(exploitLabels).Inc()
				return
			case errors.As(err, &exitErr):
				if exitErr.ExitCode() == -1 {
					task.logger.Warningf("Эксплойт завершился по сигналу: %v", exitErr)
				} else {
					task.logger.Warningf("Ошибка при выполнении эксплойта: %v", err)
				}
				q.metrics.ExploitsFailed.With(exploitLabels).Inc()

			default:
				task.logger.Errorf("Не удалось выполнить задание: %v", err)
				q.metrics.ExploitsFailed.With(exploitLabels).Inc()
			}
			q.out <- NewOutput(task, res)
		}
	}
}

func (q *simpleQueue) runExploit(ctx context.Context, task *Job) ([]byte, error) {
	cmdCtx, cancel := context.WithTimeout(ctx, task.timeout)
	defer cancel()

	cmd := task.Command(cmdCtx)
	task.logger.Infof("Запуск задания: %v", cmd)

	start := time.Now()
	exploitLabels := task.Exploit.MetricLabels()
	q.metrics.ExploitInstancesRunning.With(exploitLabels).Inc()
	defer func() {
		q.metrics.ExploitInstancesRunning.With(exploitLabels).Dec()
		q.metrics.ExploitRunTime.With(exploitLabels).Observe(time.Since(start).Seconds())
	}()

	// Процесс завершится при отмене контекста.
	out, err := cmd.CombinedOutput()
	if err != nil {
		err = fmt.Errorf("ошибка выполнения команды: %w", err)
	}
	return out, err
}
