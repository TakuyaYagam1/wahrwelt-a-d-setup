package ru.serega6531.packmate.pcap;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.pcap4j.core.PcapNativeException;
import org.pcap4j.core.PcapHandle;
import org.pcap4j.core.PcapNetworkInterface;
import org.pcap4j.core.Pcaps;
import ru.serega6531.packmate.model.enums.Protocol;
import ru.serega6531.packmate.exception.PcapInterfaceNotFoundException;
import ru.serega6531.packmate.service.ServicesService;
import ru.serega6531.packmate.service.StreamService;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class LivePcapWorker extends AbstractPcapWorker {

    private static final int PROCESSOR_QUEUE_CAPACITY = 1024;

    private final PcapNetworkInterface device;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Runnable stoppedCallback = () -> {};
    private volatile CountDownLatch loopFinished = new CountDownLatch(0);
    private volatile CountDownLatch stopFinished = new CountDownLatch(0);

    public LivePcapWorker(ServicesService servicesService,
                          StreamService streamService,
                          InetAddress localIp,
                          String interfaceName) throws PcapNativeException, UnknownHostException {
        super(servicesService, streamService, localIp);
        device = Pcaps.getDevByName(interfaceName);

        if (device == null) {
            List<String> existingInterfaces = Pcaps.findAllDevs().stream().map(PcapNetworkInterface::getName).toList();
            throw new PcapInterfaceNotFoundException(interfaceName, existingInterfaces);
        }

        BasicThreadFactory factory = new BasicThreadFactory.Builder()
                .namingPattern("pcap-processor").build();
        processorExecutorService = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(PROCESSOR_QUEUE_CAPACITY),
                factory,
                new BlockingBackpressurePolicy()
        );
    }

    @SneakyThrows
    public void start(Runnable onStarted, Runnable onStopped) throws PcapNativeException {
        log.info("Открывается сетевой интерфейс: {}", device.getName());
        var capture = device.openLive(65536, PcapNetworkInterface.PromiscuousMode.PROMISCUOUS, 100);
        pcap = capture;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch currentLoopFinished = new CountDownLatch(1);
        CountDownLatch currentStopFinished = new CountDownLatch(1);
        loopFinished = currentLoopFinished;
        stopFinished = currentStopFinished;
        try {
            applyFilter();
            stoppedCallback = onStopped;
            running.set(true);
            loopExecutorService.execute(() -> runAfterStart(startGate, currentLoopFinished, capture));
            onStarted.run();
            startGate.countDown();
        } catch (Throwable exception) {
            running.set(false);
            startGate.countDown();
            currentLoopFinished.countDown();
            currentStopFinished.countDown();
            if (capture.isOpen()) {
                capture.close();
            }
            throw exception;
        }
    }

    private void runAfterStart(CountDownLatch startGate, CountDownLatch currentLoopFinished, PcapHandle capture) {
        try {
            startGate.await();
            if (!running.get() || pcap != capture) {
                return;
            }
            log.info("Перехват сетевого трафика запущен");
            capture.loop(-1, this);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.info("Перехват сетевого трафика прерван");
        } catch (Exception exception) {
            log.error("Ошибка при перехвате сетевого пакета", exception);
        } finally {
            currentLoopFinished.countDown();
            stop(capture);
        }
    }

    @SneakyThrows
    public void stop() {
        stop(pcap);
    }

    @SneakyThrows
    private void stop(PcapHandle capture) {
        if (capture == null || pcap != capture) {
            return;
        }
        CountDownLatch currentStopFinished = stopFinished;
        if (!running.compareAndSet(true, false)) {
            awaitLoopFinished(currentStopFinished);
            return;
        }

        try {
            if (capture.isOpen()) {
                capture.breakLoop();
                capture.close();
            }
        } catch (RuntimeException exception) {
            log.warn("Не удалось сразу закрыть интерфейс захвата", exception);
        }

        try {
            awaitLoopFinished(loopFinished);
            flushProcessorQueue();
        } catch (RuntimeException exception) {
            log.error("Не удалось завершить обработку очереди захвата", exception);
        } finally {
            try {
                log.info("Перехват сетевого трафика остановлен");
                stoppedCallback.run();
            } finally {
                currentStopFinished.countDown();
            }
        }
    }

    private void awaitLoopFinished(CountDownLatch currentLoopFinished) {
        boolean interrupted = false;
        while (true) {
            try {
                currentLoopFinished.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void flushProcessorQueue() {
        flushAndWait(processorExecutorService, () -> {
            closeAllStreams(Protocol.TCP);
            closeAllStreams(Protocol.UDP);
        });
    }

    static void flushAndWait(ExecutorService executor, Runnable flushTask) {
        try {
            executor.submit(flushTask).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание завершения очереди захвата прервано", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Ошибка при сохранении незавершенных потоков", exception.getCause());
        }
    }

    @Override
    public void shutdown() {
        stop();
        shutdownExecutor(processorExecutorService, "pcap-processor");
        shutdownExecutor(loopExecutorService, "pcap-loop");
    }

    @Override
    public String getExecutorState() {
        return processorExecutorService.toString();
    }

    static final class BlockingBackpressurePolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            while (!executor.isShutdown()) {
                try {
                    if (executor.getQueue().offer(task, 100, TimeUnit.MILLISECONDS)) {
                        if (executor.isShutdown() && executor.getQueue().remove(task)) {
                            throw new RejectedExecutionException("Очередь обработки захвата уже остановлена");
                        }
                        return;
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new RejectedExecutionException("Ожидание свободного места в очереди захвата прервано", exception);
                }
            }
            throw new RejectedExecutionException("Очередь обработки захвата уже остановлена");
        }
    }
}
