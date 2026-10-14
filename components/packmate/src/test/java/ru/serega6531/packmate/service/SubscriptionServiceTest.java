package ru.serega6531.packmate.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;
import ru.serega6531.packmate.model.enums.SubscriptionMessageType;
import ru.serega6531.packmate.model.pojo.SubscriptionMessage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SubscriptionServiceTest {
    @Test
    void serializesConcurrentMessagesAndRemovesWrappedSession() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("browser");
        when(session.isOpen()).thenReturn(true);
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        doAnswer(invocation -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            sending.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } finally {
                active.decrementAndGet();
            }
            return null;
        }).when(session).sendMessage(any());

        SubscriptionService service = new SubscriptionService(JsonMapper.builder().build());
        service.addSubscriber(session);
        SubscriptionMessage message = new SubscriptionMessage(SubscriptionMessageType.PCAP_STARTED, null);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> service.broadcast(message));
            try {
                assertTrue(sending.await(5, TimeUnit.SECONDS));
                service.broadcast(message);
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, maximum.get());
        verify(session, times(2)).sendMessage(any());
        service.removeSubscriber(session);
        service.broadcast(message);
        verify(session, times(2)).sendMessage(any());
    }
}
