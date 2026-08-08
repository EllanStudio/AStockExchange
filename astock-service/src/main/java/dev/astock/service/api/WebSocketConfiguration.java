package dev.astock.service.api;

import dev.astock.service.config.AStockProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Configuration
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {
    private final QuoteWebSocketHandler handler;
    private final byte[] expectedKey;

    public WebSocketConfiguration(QuoteWebSocketHandler handler, AStockProperties properties) {
        this.handler = handler;
        this.expectedKey = properties.api().key().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/quotes")
                .addInterceptors(new ApiKeyHandshakeInterceptor(expectedKey))
                .setAllowedOrigins();
    }

    private record ApiKeyHandshakeInterceptor(byte[] expectedKey) implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            String key = request.getHeaders().getFirst(ApiKeyFilter.HEADER);
            boolean valid = key != null && MessageDigest.isEqual(expectedKey, key.getBytes(StandardCharsets.UTF_8));
            if (!valid) response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return valid;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
            // Nothing to release.
        }
    }
}
