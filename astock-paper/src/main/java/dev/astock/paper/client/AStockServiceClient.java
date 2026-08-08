package dev.astock.paper.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static dev.astock.paper.client.ApiModels.*;

public final class AStockServiceClient implements AutoCloseable {
    private static final Type QUOTE_LIST = new TypeToken<List<Quote>>() { }.getType();
    private static final Type POSITION_LIST = new TypeToken<List<Position>>() { }.getType();
    private static final Type ORDER_LIST = new TypeToken<List<Order>>() { }.getType();

    private final URI baseUri;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http;
    private final Gson gson = new Gson();

    public AStockServiceClient(String baseUrl, String apiKey, Duration timeout) {
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public URI webSocketUri() {
        String value = baseUri.toString();
        if (value.startsWith("https://")) value = "wss://" + value.substring(8);
        else if (value.startsWith("http://")) value = "ws://" + value.substring(7);
        return URI.create(value + "/ws/quotes");
    }

    public HttpClient httpClient() {
        return http;
    }

    public String apiKey() {
        return apiKey;
    }

    public CompletableFuture<List<Quote>> quotes() {
        return get("/api/v1/market/quotes", QUOTE_LIST);
    }

    public CompletableFuture<Quote> quote(String symbol, boolean refresh) {
        return get("/api/v1/market/quotes/" + encode(symbol) + "?refresh=" + refresh, Quote.class);
    }

    public CompletableFuture<Account> account(UUID player) {
        return get("/api/v1/accounts/" + player, Account.class);
    }

    public CompletableFuture<List<Position>> positions(UUID player) {
        return get("/api/v1/accounts/" + player + "/positions", POSITION_LIST);
    }

    public CompletableFuture<List<Order>> orders(UUID player) {
        return get("/api/v1/accounts/" + player + "/orders?limit=50", ORDER_LIST);
    }

    public CompletableFuture<Order> place(PlaceOrderRequest request) {
        return post("/api/v1/orders", request, Order.class);
    }

    public CompletableFuture<Order> cancel(String orderId, UUID player) {
        return delete("/api/v1/orders/" + encode(orderId) + "?playerUuid=" + player, Order.class);
    }

    public CompletableFuture<Transfer> beginTransfer(BeginTransferRequest request) {
        return post("/api/v1/transfers", request, Transfer.class);
    }

    public CompletableFuture<Transfer> confirmEconomy(String transferId) {
        return post("/api/v1/transfers/" + encode(transferId) + "/confirm-economy", null, Transfer.class);
    }

    public CompletableFuture<Transfer> compensate(String transferId, String reason) {
        return post("/api/v1/transfers/" + encode(transferId) + "/compensate",
                new CompensateRequest(reason), Transfer.class);
    }

    public CompletableFuture<JsonElement> adminGet(String suffix) {
        return get("/api/v1/admin/" + suffix, JsonElement.class);
    }

    public CompletableFuture<JsonElement> adminPost(String suffix, Object body) {
        return post("/api/v1/admin/" + suffix, body, JsonElement.class);
    }

    private <T> CompletableFuture<T> get(String path, Type type) {
        return send(path, "GET", null).thenApply(body -> gson.fromJson(body, type));
    }

    private <T> CompletableFuture<T> post(String path, Object body, Type type) {
        String json = body == null ? "{}" : gson.toJson(body);
        return send(path, "POST", json).thenApply(response -> gson.fromJson(response, type));
    }

    private <T> CompletableFuture<T> delete(String path, Type type) {
        return send(path, "DELETE", null).thenApply(response -> gson.fromJson(response, type));
    }

    private CompletableFuture<String> send(String path, String method, String body) {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(timeout)
                .header("X-AStock-Key", apiKey)
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        String message = "AStockService HTTP " + response.statusCode();
                        try {
                            JsonElement parsed = JsonParser.parseString(response.body());
                            if (parsed.isJsonObject() && parsed.getAsJsonObject().has("message")) {
                                message = parsed.getAsJsonObject().get("message").getAsString();
                            }
                        } catch (RuntimeException ignored) {
                            // Keep the status-only message.
                        }
                        throw new ServiceException(response.statusCode(), message);
                    }
                    return response.body();
                });
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        // JDK HttpClient has no close operation.
    }

    public static final class ServiceException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;

        public ServiceException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
