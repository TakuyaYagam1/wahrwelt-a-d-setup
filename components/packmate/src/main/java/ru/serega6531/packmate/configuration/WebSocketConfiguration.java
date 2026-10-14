package ru.serega6531.packmate.configuration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import ru.serega6531.packmate.controller.WebSocketHandler;

@EnableWebSocket
@Configuration
public class WebSocketConfiguration implements WebSocketConfigurer {

    private final WebSocketHandler webSocketHandler;

    @Autowired
    public WebSocketConfiguration(WebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(webSocketHandler, "/api/ws");
    }

}
