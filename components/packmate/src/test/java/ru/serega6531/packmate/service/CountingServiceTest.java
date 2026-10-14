package ru.serega6531.packmate.service;

import org.junit.jupiter.api.Test;
import ru.serega6531.packmate.model.pojo.CountersHolder;
import ru.serega6531.packmate.model.pojo.SubscriptionMessage;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class CountingServiceTest {
    @Test
    void doesNotLoseStreamsWhenSendingAndResettingCounters() throws Exception {
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        AtomicInteger streams = new AtomicInteger();
        AtomicInteger packets = new AtomicInteger();
        doAnswer(invocation -> {
            SubscriptionMessage message = invocation.getArgument(0);
            CountersHolder counters = (CountersHolder) message.getValue();
            assertEquals(counters.totalStreams(), counters.servicesStreams().values().stream().mapToInt(Integer::intValue).sum());
            streams.addAndGet(counters.totalStreams());
            packets.addAndGet(counters.totalPackets());
            return null;
        }).when(subscriptions).broadcast(any());

        CountingService service = new CountingService(subscriptions);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var producer = executor.submit(() -> {
                for (int i = 0; i < 5000; i++) service.countStream(i % 3 + 80, 2);
            });
            var sender = executor.submit(() -> {
                for (int i = 0; i < 5000; i++) service.sendCounters();
            });
            producer.get(10, TimeUnit.SECONDS);
            sender.get(10, TimeUnit.SECONDS);
        }
        service.sendCounters();
        assertEquals(5000, streams.get());
        assertEquals(10000, packets.get());
    }
}
