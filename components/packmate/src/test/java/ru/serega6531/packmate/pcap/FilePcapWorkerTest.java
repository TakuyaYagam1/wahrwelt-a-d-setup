package ru.serega6531.packmate.pcap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pcap4j.core.PcapHandle;
import org.pcap4j.core.PcapNativeException;
import org.pcap4j.core.PcapPacket;
import ru.serega6531.packmate.service.ServicesService;
import ru.serega6531.packmate.service.StreamService;

import java.io.File;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FilePcapWorkerTest {

    @Test
    void nativeReadFailureStopsCaptureAndNotifiesCaller(@TempDir Path tempDir) throws Exception {
        PcapHandle capture = mock(PcapHandle.class);
        when(capture.isOpen()).thenReturn(true);
        when(capture.getNextPacketEx()).thenThrow(mock(PcapNativeException.class));
        CountDownLatch stopped = new CountDownLatch(1);
        FilePcapWorker worker = worker(tempDir, capture, null);

        worker.start(() -> {}, stopped::countDown);

        assertTrue(stopped.await(5, TimeUnit.SECONDS));
        verify(capture, times(1)).getNextPacketEx();
        verify(capture).close();
        worker.shutdown();
    }

    @Test
    void packetProcessingFailureStillStopsCapture(@TempDir Path tempDir) throws Exception {
        PcapHandle capture = mock(PcapHandle.class);
        when(capture.isOpen()).thenReturn(true);
        when(capture.getNextPacketEx()).thenReturn(mock(PcapPacket.class));
        CountDownLatch stopped = new CountDownLatch(1);
        FilePcapWorker worker = worker(tempDir, capture, new IllegalStateException("bad packet"));

        worker.start(() -> {}, stopped::countDown);

        assertTrue(stopped.await(5, TimeUnit.SECONDS));
        verify(capture, times(1)).getNextPacketEx();
        verify(capture).close();
        worker.shutdown();
    }

    private FilePcapWorker worker(Path tempDir, PcapHandle capture, RuntimeException processingFailure) throws Exception {
        File input = tempDir.resolve("capture.pcap").toFile();
        assertTrue(input.createNewFile());
        return new FilePcapWorker(
                mock(ServicesService.class),
                mock(StreamService.class),
                InetAddress.getLoopbackAddress(),
                input.getAbsolutePath()
        ) {
            @Override
            protected PcapHandle openCapture() {
                return capture;
            }

            @Override
            protected void gotPacket(PcapPacket packet, PcapHandle currentCapture) {
                if (processingFailure != null) {
                    throw processingFailure;
                }
            }
        };
    }
}
