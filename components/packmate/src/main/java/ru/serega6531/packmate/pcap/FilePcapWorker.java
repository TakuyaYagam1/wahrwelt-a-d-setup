package ru.serega6531.packmate.pcap;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.threads.InlineExecutorService;
import org.pcap4j.core.PcapNativeException;
import org.pcap4j.core.PcapHandle;
import org.pcap4j.core.NotOpenException;
import org.pcap4j.core.Pcaps;
import org.pcap4j.core.PcapPacket;
import ru.serega6531.packmate.exception.PcapFileNotFoundException;
import ru.serega6531.packmate.model.enums.Protocol;
import ru.serega6531.packmate.service.ServicesService;
import ru.serega6531.packmate.service.StreamService;

import java.io.EOFException;
import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class FilePcapWorker extends AbstractPcapWorker {

    private final File directory;

    private final File file;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Runnable stoppedCallback = () -> {};
    private volatile CountDownLatch loopFinished = new CountDownLatch(0);
    private volatile CountDownLatch stopFinished = new CountDownLatch(0);

    public FilePcapWorker(ServicesService servicesService,
                          StreamService streamService,
                          InetAddress localIp,
                          String filename) throws UnknownHostException {
        super(servicesService, streamService, localIp);

        File configuredFile = new File(filename);
        if (configuredFile.isAbsolute()) {
            file = configuredFile;
            directory = configuredFile.getParentFile();
        } else {
            directory = new File("pcaps");
            file = new File(directory, filename);
        }
        validateFileExists();

        processorExecutorService = new InlineExecutorService();
    }

    @SneakyThrows
    @Override
    public void start(Runnable onStarted, Runnable onStopped) {
        log.info("Открывается файл захвата: {}", file.getAbsolutePath());
        PcapHandle capture = openCapture();
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

    protected PcapHandle openCapture() throws PcapNativeException {
        return Pcaps.openOffline(file.getAbsolutePath());
    }

    private void runAfterStart(CountDownLatch startGate, CountDownLatch loopFinished, PcapHandle capture) {
        try {
            startGate.await();
            if (running.get() && pcap == capture) {
                runScan(capture, loopFinished);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.info("Обработка файла захвата прервана");
        } catch (RuntimeException exception) {
            log.error("Не удалось обработать файл захвата", exception);
        } finally {
            loopFinished.countDown();
            stop(capture);
        }
    }

    private void runScan(PcapHandle capture, CountDownLatch loopFinished) {
        try {
            while (running.get() && pcap == capture && capture.isOpen()) {
                try {
                    final PcapPacket packet = capture.getNextPacketEx();
                    gotPacket(packet, capture);
                } catch (PcapNativeException | NotOpenException | TimeoutException exception) {
                    log.error("Ошибка чтения файла захвата: {}", exception.getMessage());
                    break;
                } catch (EOFException exception) {
                    log.info("Чтение файла захвата завершено");
                    break;
                } catch (RuntimeException exception) {
                    log.error("Ошибка обработки пакета из файла захвата", exception);
                    break;
                }
            }
        } finally {
            loopFinished.countDown();
            stop(capture);
        }
    }

    @SneakyThrows
    public void stop() {
        stop(pcap);
    }

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
                capture.close();
                log.info("Файл захвата закрыт");
            }
        } catch (RuntimeException exception) {
            log.warn("Не удалось закрыть файл захвата", exception);
        }

        try {
            awaitLoopFinished(loopFinished);
            closeAllStreams(Protocol.TCP);
            closeAllStreams(Protocol.UDP);
        } finally {
            try {
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

    @Override
    public void shutdown() {
        stop();
        shutdownExecutor(loopExecutorService, "pcap-loop");
        shutdownExecutor(processorExecutorService, "pcap-inline");
    }

    @Override
    public String getExecutorState() {
        return "inline";
    }

    private void validateFileExists() {
        if (!file.exists()) {
            throw new PcapFileNotFoundException(file, directory);
        }
    }
}
