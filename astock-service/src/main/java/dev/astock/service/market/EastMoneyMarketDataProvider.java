package dev.astock.service.market;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.astock.domain.quote.TradingStatus;
import dev.astock.service.config.AStockProperties;
import dev.astock.service.security.SecurityInfo;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Development/non-commercial adapter. Operators are responsible for data licensing. */
@Component
public class EastMoneyMarketDataProvider implements MarketDataProvider {
    private static final URI BULK_URI = URI.create("https://82.push2.eastmoney.com/api/qt/clist/get"
            + "?pn=1&pz=6000&po=1&np=1&fltt=2&invt=2&fid=f3"
            + "&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23"
            + "&fields=f2,f3,f5,f6,f12,f14,f15,f16,f17,f18,f124");

    private final HttpClient client;
    private final AStockProperties properties;
    private final Clock clock;

    public EastMoneyMarketDataProvider(AStockProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.client = HttpClient.newBuilder().connectTimeout(properties.data().connectTimeout()).build();
    }

    @Override
    public String name() {
        return "eastmoney";
    }

    @Override
    public List<ProviderQuote> fetchBulk(List<SecurityInfo> securities) {
        Map<String, SecurityInfo> wanted = new HashMap<>();
        securities.forEach(security -> wanted.put(security.symbol(), security));
        JsonObject root = request(BULK_URI);
        JsonElement diff = root.getAsJsonObject("data").get("diff");
        if (diff == null || !diff.isJsonArray()) throw new MarketDataException("EastMoney response has no data.diff");
        return diff.getAsJsonArray().asList().stream()
                .map(JsonElement::getAsJsonObject)
                .map(this::bulkQuote)
                .filter(quote -> wanted.containsKey(quote.symbol()))
                .toList();
    }

    @Override
    public Optional<ProviderQuote> fetchDetail(SecurityInfo security) {
        if (!security.exchange().equals("SH") && !security.exchange().equals("SZ")) {
            return Optional.empty();
        }
        String secId = security.exchange().equals("SH") ? "1." : "0.";
        secId += security.symbol().substring(3);
        String query = "https://push2.eastmoney.com/api/qt/stock/get?fltt=2&invt=2&secid="
                + URLEncoder.encode(secId, StandardCharsets.UTF_8)
                + "&fields=f19,f20,f39,f40,f43,f44,f45,f46,f47,f48,f57,f58,f60,f124";
        JsonObject root = request(URI.create(query));
        JsonElement data = root.get("data");
        if (data == null || data.isJsonNull()) return Optional.empty();
        return Optional.of(detailQuote(data.getAsJsonObject(), security));
    }

    private JsonObject request(URI uri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(properties.data().requestTimeout())
                    .header("User-Agent", "AStockService/1.0")
                    .GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new MarketDataException("EastMoney returned HTTP " + response.statusCode());
            }
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MarketDataException("EastMoney request interrupted", exception);
        } catch (RuntimeException | java.io.IOException exception) {
            throw new MarketDataException("EastMoney request failed", exception);
        }
    }

    private ProviderQuote bulkQuote(JsonObject node) {
        String code = text(node, "f12");
        String symbol = exchange(code) + "." + code;
        BigDecimal last = decimal(node, "f2");
        return new ProviderQuote(symbol, text(node, "f14"), timestamp(node), last,
                decimal(node, "f18"), decimal(node, "f17"), decimal(node, "f15"), decimal(node, "f16"),
                null, 0, null, 0, number(node, "f5"), number(node, "f6"),
                last == null ? TradingStatus.HALTED : TradingStatus.TRADING, name());
    }

    private ProviderQuote detailQuote(JsonObject node, SecurityInfo security) {
        BigDecimal last = decimal(node, "f43");
        BigDecimal bid = decimal(node, "f19");
        BigDecimal ask = decimal(node, "f39");
        TradingStatus status = last != null && bid != null && ask != null
                ? TradingStatus.TRADING : TradingStatus.HALTED;
        return new ProviderQuote(security.symbol(), security.name(), timestamp(node), last,
                decimal(node, "f60"), decimal(node, "f46"), decimal(node, "f44"), decimal(node, "f45"),
                bid, number(node, "f20"), ask, number(node, "f40"), number(node, "f47"), number(node, "f48"),
                status, name());
    }

    private long timestamp(JsonObject node) {
        long seconds = number(node, "f124");
        return seconds > 0 ? Math.multiplyExact(seconds, 1_000L) : clock.millis();
    }

    private static String exchange(String code) {
        return code != null && (code.startsWith("5") || code.startsWith("6") || code.startsWith("9")) ? "SH" : "SZ";
    }

    private static String text(JsonObject node, String field) {
        JsonElement value = node.get(field);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static BigDecimal decimal(JsonObject node, String field) {
        JsonElement value = node.get(field);
        if (value == null || value.isJsonNull() || "-".equals(value.getAsString())) return null;
        try {
            BigDecimal result = value.getAsBigDecimal();
            return result.signum() > 0 ? result : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static long number(JsonObject node, String field) {
        JsonElement value = node.get(field);
        if (value == null || value.isJsonNull() || "-".equals(value.getAsString())) return 0;
        try {
            return value.getAsBigDecimal().longValue();
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
