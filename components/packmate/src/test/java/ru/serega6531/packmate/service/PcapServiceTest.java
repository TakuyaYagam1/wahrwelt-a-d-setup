package ru.serega6531.packmate.service;

import org.junit.jupiter.api.Test;
import org.pcap4j.core.PcapNativeException;
import ru.serega6531.packmate.pcap.PcapWorker;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PcapServiceTest {

    @Test
    void failedStartCanBeRetriedAndOnlySuccessfulStartIsBroadcast() throws PcapNativeException {
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        PcapWorker worker = mock(PcapWorker.class);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) {
                throw new PcapNativeException("capture device unavailable");
            }
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(worker).start(any(), any());
        PcapService service = new PcapService(subscriptions, worker);

        assertThrows(PcapNativeException.class, service::start);
        assertFalse(service.isStarted());

        service.start();
        assertTrue(service.isStarted());
        verify(worker, times(2)).start(any(), any());
        verify(subscriptions, times(1)).broadcast(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completedCaptureCanBeStartedAgain() throws PcapNativeException {
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        PcapWorker worker = mock(PcapWorker.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(worker).start(any(), any());
        PcapService service = new PcapService(subscriptions, worker);

        service.start();
        assertFalse(service.isStarted());

        service.start();
        assertFalse(service.isStarted());

        verify(worker, times(2)).start(any(), any());
        verify(subscriptions, times(4)).broadcast(org.mockito.ArgumentMatchers.any());
    }
}
