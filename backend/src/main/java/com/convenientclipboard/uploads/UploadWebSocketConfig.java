package com.convenientclipboard.uploads;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class UploadWebSocketConfig implements WebSocketConfigurer {
    private final UploadWebSocketHandler handler;

    public UploadWebSocketConfig(UploadWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Spring's default same-origin policy also applies to the upgrade request.
        registry.addHandler(handler, "/api/upload-stream");
    }
}
