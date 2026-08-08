package dev.astock.paper.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.astock.paper.client.ApiModels.Quote;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class QuoteStream implements WebSocket.Listener, AutoCloseable {
    private final AStockServiceClient client;
    private final int minimumReconnectSeconds;
    private final int maximumReconnectSeconds;
    private final Consumer<String> logger;
    private final Gson gson = new Gson();
    private final Map<String, Quote> quotes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "astock-quote-reconnect");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final StringBuilder fragments = new StringBuilder();
    private volatile boolean connected;
    private volatile int reconnectSeconds;
    private volatile long lastMessageAt;

    public QuoteStream(AStockServiceClient client, int minimumReconnectSeconds,
                       int maximumReconnectSeconds, Consumer<String> logger) {
        this.client = client;
        this.minimumReconnectSeconds = Math.max(1, minimumReconnectSeconds);
        this.maximumReconnectSeconds = Math.max(this.minimumReconnectSeconds, maximumReconnectSeconds);
        this.reconnectSeconds = this.minimumReconnectSeconds;
        this.logger = logger;
    }

    public void start() {
        connect();
    }

    public Quote get(String symbol) {
        return quotes.get(symbol);
    }

    public List<Quote> snapshot() {
        return quotes.values().stream().sorted(java.util.Comparator.comparing(Quote::symbol)).toList();
    }

    public boolean connected() {
        return connected;
    }

    public long lastMessageAt() {
        return lastMessageAt;
    }

    private void connect() {
        if (closed.get()) return;
        reconnectScheduled.set(false);
        client.httpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .header("X-AStock-Key", client.apiKey())
                .buildAsync(client.webSocketUri(), this)
                .whenComplete((webSocket, failure) -> {
                    if (failure != null) {
                        logger.accept("行情 WebSocket 连接失败: " + rootMessage(failure));
                        scheduleReconnect();
                    } else {
                        socket.set(webSocket);
                    }
                });
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        connected = true;
        reconnectSeconds = minimumReconnectSeconds;
        lastMessageAt = System.currentTimeMillis();
        WebSocket.Listener.super.onOpen(webSocket);
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        synchronized (fragments) {
            fragments.append(data);
            if (last) {
                parse(fragments.toString());
                fragments.setLength(0);
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
        webSocket.request(1);
        return webSocket.sendPong(message);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        connected = false;
        socket.compareAndSet(webSocket, null);
        if (!closed.get()) scheduleReconnect();
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        connected = false;
        socket.compareAndSet(webSocket, null);
        logger.accept("行情 WebSocket 异常: " + rootMessage(error));
        scheduleReconnect();
    }

    private void parse(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String type = root.get("type").getAsString();
            if ("SNAPSHOT".equals(type)) {
                for (JsonElement element : root.getAsJsonArray("quotes")) update(gson.fromJson(element, Quote.class));
            } else if ("QUOTE".equals(type)) {
                update(gson.fromJson(root.get("quote"), Quote.class));
            }
            lastMessageAt = System.currentTimeMillis();
        } catch (RuntimeException exception) {
            logger.accept("无法解析行情推送: " + exception.getMessage());
        }
    }

    private void update(Quote quote) {
        quotes.compute(quote.symbol(), (symbol, existing) ->
                existing == null || quote.sequence() > existing.sequence() ? quote : existing);
    }

    private synchronized void scheduleReconnect() {
        if (closed.get() || !reconnectScheduled.compareAndSet(false, true)) return;
        int delay = reconnectSeconds;
        reconnectSeconds = Math.min(maximumReconnectSeconds, reconnectSeconds * 2);
        reconnectExecutor.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable value = throwable;
        while (value.getCause() != null) value = value.getCause();
        return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        connected = false;
        WebSocket webSocket = socket.getAndSet(null);
        if (webSocket != null) webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "plugin disabled");
        reconnectExecutor.shutdownNow();
    }
}
