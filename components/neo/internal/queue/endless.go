package queue

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"os"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/sirupsen/logrus"

	"github.com/c4t-but-s4d/neo/v2/pkg/neosync"
)

const (
	endlessDebounce = 3 * time.Second
)

// Проверки типов во время компиляции.
var (
	_ Queue = (*endlessQueue)(nil)
)

type endlessQueue struct {
	out     chan *Output
	c       chan *Job
	maxJobs int
	metrics *Metrics
	logger  *logrus.Entry
}

func NewEndlessQueue(maxJobs int) Queue {
	queueID := uuid.NewString()[:8]
	return &endlessQueue{
		out:     make(chan *Output, resultBufferSize),
		c:       make(chan *Job, jobBufferSize),
		maxJobs: maxJobs,
		metrics: NewMetrics("neo", queueID, TypeEndless),
		logger: logrus.WithFields(logrus.Fields{
			"component": "endless_queue",
			"id":        queueID,
		}),
	}
}

func (q *endlessQueue) Type() Type {
	return TypeEndless
}

func (q *endlessQueue) Size() int {
	return len(q.c)
}

// Start выполняется синхронно. Чтобы остановить очередь, отмените контекст.
func (q *endlessQueue) Start(ctx context.Context) {
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

func (q *endlessQueue) Results() <-chan *Output {
	return q.out
}

func (q *endlessQueue) Add(task *Job) error {
	select {
	case q.c <- task:
		return nil
	default:
		return ErrQueueFull
	}
}

func (q *endlessQueue) String() string {
	return "EndlessQueue"
}

func (q *endlessQueue) worker(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case job := <-q.c:
			for {
				select {
				case <-ctx.Done():
					return
				default:
				}

				err := q.runExploit(ctx, job)
				switch {
				case errors.Is(err, context.Canceled):
					return
				case err != nil:
					job.logger.Errorf("Бесконечный эксплойт завершился с ошибкой: %v", err)
					neosync.Sleep(ctx, endlessDebounce)
				default:
					job.logger.Errorf("Бесконечный эксплойт завершился неожиданно")
					neosync.Sleep(ctx, endlessDebounce)
				}
			}
		}
	}
}

func (q *endlessQueue) runExploit(ctx context.Context, job *Job) error {
	cmd := job.Command(ctx)
	job.logger.Infof("Бесконечный запуск задания: %s", cmd)

	// os.Pipe работает быстрее io.Pipe.
	r, w, err := os.Pipe()
	if err != nil {
		return fmt.Errorf("создание канала вывода: %w", err)
	}

	exploitLabels := job.Exploit.MetricLabels()

	errC := make(chan error, 1)
	go func() {
		defer func(w *os.File) {
			if err := w.Close(); err != nil {
				job.logger.Errorf("Ошибка закрытия канала вывода: %v", err)
			}
		}(w)

		cmd.Stdout = w
		cmd.Stderr = w

		q.metrics.ExploitInstancesRunning.With(exploitLabels).Inc()
		defer q.metrics.ExploitInstancesRunning.With(exploitLabels).Dec()

		errC <- cmd.Run()
	}()

	readDone := make(chan struct{})
	defer func() {
		job.logger.Infof("Ожидание завершения чтения вывода")
		<-readDone
	}()

	go func() {
		defer close(readDone)
		scanner := bufio.NewScanner(r)
		for scanner.Scan() {
			q.out <- NewOutput(job, scanner.Bytes())
		}
		if err := scanner.Err(); err != nil && ctx.Err() == nil {
			job.logger.Errorf("Ошибка чтения вывода бесконечного задания: %v", err)
		}
	}()

	select {
	case <-ctx.Done():
		<-errC
		q.metrics.ExploitsFinished.With(exploitLabels).Inc()
		return nil
	case err := <-errC:
		// Ожидаемая ошибка при отмене контекста.
		if ctx.Err() != nil {
			q.metrics.ExploitsFinished.With(exploitLabels).Inc()
			return nil
		}

		job.logger.Errorf("Бесконечный эксплойт %v завершился: %v", job, err)
		q.metrics.ExploitsFailed.With(exploitLabels).Inc()
		if err != nil {
			return fmt.Errorf("ошибка бесконечного эксплойта %v: %w", job, err)
		}
		return nil
	}
}
