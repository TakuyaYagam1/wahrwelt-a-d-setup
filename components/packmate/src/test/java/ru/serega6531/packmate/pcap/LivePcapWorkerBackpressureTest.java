package ru.serega6531.packmate.pcap;

import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivePcapWorkerBackpressureTest {

    @Test
    void fullQueueAppliesBackpressureAndFlushRunsAfterEarlierPackets() throws Exception {
        var factory = new BasicThreadFactory.Builder().namingPattern("pcap-test-processor").build();
        var executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                factory,
                new LivePcapWorker.BlockingBackpressurePolicy()
        );
        var order = Collections.synchronizedList(new ArrayList<String>());
        var firstTaskStarted = new CountDownLatch(1);
        var releaseFirstTask = new CountDownLatch(1);
        var flushCompleted = new CountDownLatch(1);

        try {
            executor.execute(() -> {
                order.add("пакет-1");
                firstTaskStarted.countDown();
                try {
                    releaseFirstTask.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(firstTaskStarted.await(5, TimeUnit.SECONDS));
            executor.execute(() -> order.add("пакет-2"));

            Thread flushThread = new Thread(() -> LivePcapWorker.flushAndWait(executor, () -> {
                order.add("закрытие-потоков");
                flushCompleted.countDown();
            }));
            flushThread.start();

            assertFalse(flushCompleted.await(100, TimeUnit.MILLISECONDS));
            assertEquals(1, executor.getQueue().size());
            releaseFirstTask.countDown();
            assertTrue(flushCompleted.await(5, TimeUnit.SECONDS));
            flushThread.join(TimeUnit.SECONDS.toMillis(5));

            assertEquals(List.of("пакет-1", "пакет-2", "закрытие-потоков"), order);
        } finally {
            releaseFirstTask.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
