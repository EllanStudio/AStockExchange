package dev.astock.service.api;

import com.google.gson.Gson;
import dev.astock.domain.quote.CanonicalQuote;
import dev.astock.service.market.QuoteCoordinator;
import dev.astock.service.market.QuoteUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class QuoteWebSocketHandler extends TextWebSocketHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuoteWebSocketHandler.class);
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final QuoteCoordinator quotes;
    private final Gson gson = new Gson();

    public QuoteWebSocketHandler(QuoteCoordinator quotes) {
        this.quotes = quotes;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        sessions.add(session);
        send(session, new SnapshotMessage("SNAPSHOT", quotes.snapshot()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws IOException {
        sessions.remove(session);
        session.close(CloseStatus.SERVER_ERROR);
    }

    @EventListener
    public void quoteUpdated(QuoteUpdatedEvent event) {
        QuoteMessage message = new QuoteMessage("QUOTE", event.quote(), event.executable());
        for (WebSocketSession session : sessions) {
            try {
                send(session, message);
            } catch (IOException exception) {
                sessions.remove(session);
                LOGGER.debug("Removing failed quote WebSocket session {}", session.getId(), exception);
            }
        }
    }

    private void send(WebSocketSession session, Object value) throws IOException {
        synchronized (session) {
            if (session.isOpen()) session.sendMessage(new TextMessage(gson.toJson(value)));
        }
    }

    private record SnapshotMessage(String type, List<CanonicalQuote> quotes) {
    }

    private record QuoteMessage(String type, CanonicalQuote quote, boolean executable) {
    }
}
