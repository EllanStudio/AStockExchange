package dev.astock.domain.quote;

import java.util.Locale;
import java.util.Objects;

/**
 * Provider-neutral quote. Prices use 1/10,000 CNY, quantities use shares and
 * timestamps use Unix epoch milliseconds.
 */
public record CanonicalQuote(
        int securityId,
        String symbol,
        long sequence,
        long sourceTimestamp,
        long receivedTimestamp,
        long lastPrice,
        long previousClose,
        long openPrice,
        long highPrice,
        long lowPrice,
        long bid1Price,
        long bid1Volume,
        long ask1Price,
        long ask1Volume,
        long volume,
        long turnover,
        TradingStatus status,
        QuoteQuality quality,
        String source
) {
    public CanonicalQuote {
        if (securityId <= 0) {
            throw new IllegalArgumentException("securityId must be positive");
        }
        symbol = normalizeSymbol(symbol);
        if (sequence < 0 || sourceTimestamp < 0 || receivedTimestamp < 0) {
            throw new IllegalArgumentException("sequence and timestamps must not be negative");
        }
        status = Objects.requireNonNull(status, "status");
        quality = Objects.requireNonNull(quality, "quality");
        source = Objects.requireNonNull(source, "source").trim();
        if (source.isEmpty()) {
            throw new IllegalArgumentException("source must not be blank");
        }
    }

    public CanonicalQuote withQuality(QuoteQuality newQuality) {
        return new CanonicalQuote(
                securityId, symbol, sequence, sourceTimestamp, receivedTimestamp,
                lastPrice, previousClose, openPrice, highPrice, lowPrice,
                bid1Price, bid1Volume, ask1Price, ask1Volume, volume, turnover,
                status, newQuality, source
        );
    }

    public boolean hasExecutableBook() {
        return bid1Price > 0 && bid1Volume > 0 && ask1Price > 0 && ask1Volume > 0;
    }

    public static String normalizeSymbol(String value) {
        Objects.requireNonNull(value, "symbol");
        String symbol = value.trim().toUpperCase(Locale.ROOT);
        if (!symbol.matches("(?:SH|SZ)\\.\\d{6}")) {
            throw new IllegalArgumentException("symbol must look like SH.600519 or SZ.000001");
        }
        return symbol;
    }
}
