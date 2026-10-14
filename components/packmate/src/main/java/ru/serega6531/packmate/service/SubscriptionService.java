package ru.serega6531.packmate.service;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import ru.serega6531.packmate.model.pojo.SubscriptionMessage;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class SubscriptionService {

    private final Map<String, WebSocketSession> subscribers = new ConcurrentHashMap<>();

    private final ObjectMapper mapper;

    @Autowired
    public SubscriptionService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void addSubscriber(WebSocketSession session) {
        subscribers.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, 5000, 1024 * 1024));
        log.info("Подписан клиент: {}", session.getId());
    }

    public void removeSubscriber(WebSocketSession session) {
        subscribers.remove(session.getId());
        log.info("Отписан клиент: {}", session.getId());
    }

    /**
     * Вызов потокобезопасный
     */
    @SneakyThrows
    public void broadcast(SubscriptionMessage message) {
        final TextMessage messageJson = objectToTextMessage(message);
        subscribers.forEach((id, s) -> {
            try {
                s.sendMessage(messageJson);
            } catch (IOException | IllegalStateException e) {
                subscribers.remove(id, s);
                log.warn("Соединение WebSocket закрыто после ошибки отправки: {}", id);
                try {
                    s.close(CloseStatus.SERVER_ERROR);
                } catch (IOException ignored) {
                    // Соединение уже недоступно.
                }
            }
        });
    }

    private TextMessage objectToTextMessage(SubscriptionMessage object) throws JacksonException {
        return new TextMessage(mapper.writeValueAsString(object));
    }

}
