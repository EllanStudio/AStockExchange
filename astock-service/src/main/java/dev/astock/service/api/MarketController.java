package dev.astock.service.api;

import dev.astock.domain.quote.CanonicalQuote;
import dev.astock.service.market.MarketDataException;
import dev.astock.service.market.QuoteCoordinator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/v1/market")
public class MarketController {
    private final QuoteCoordinator quotes;

    public MarketController(QuoteCoordinator quotes) {
        this.quotes = quotes;
    }

    @GetMapping("/quotes")
    public List<CanonicalQuote> quotes() {
        return quotes.snapshot();
    }

    @GetMapping("/quotes/{symbol}")
    public CanonicalQuote quote(@PathVariable String symbol,
                                @RequestParam(defaultValue = "false") boolean refresh) {
        String normalized = normalizePathSymbol(symbol);
        if (refresh) {
            try {
                return quotes.refreshDetail(normalized).get(Duration.ofSeconds(7).toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("quote refresh interrupted", exception);
            } catch (TimeoutException exception) {
                throw new MarketDataException("quote refresh timed out", exception);
            } catch (ExecutionException exception) {
                if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
                throw new MarketDataException("quote refresh failed", exception.getCause());
            }
        }
        return quotes.current(normalized).orElseThrow(() -> new IllegalArgumentException("unknown quote: " + symbol));
    }

    @GetMapping("/status")
    public QuoteCoordinator.QuoteServiceStatus status() {
        return quotes.status();
    }

    static String normalizePathSymbol(String value) {
        String symbol = value.trim().toUpperCase(Locale.ROOT).replace('_', '.');
        if (symbol.matches("\\d{6}")) {
            symbol = (symbol.startsWith("5") || symbol.startsWith("6") || symbol.startsWith("9") ? "SH." : "SZ.")
                    + symbol;
        } else if (symbol.matches("\\d{1,5}")) {
            symbol = "HK." + "0".repeat(5 - symbol.length()) + symbol;
        } else if (symbol.matches("HK\\.\\d{1,5}")) {
            String ticker = symbol.substring(3);
            symbol = "HK." + "0".repeat(5 - ticker.length()) + ticker;
        } else if (!symbol.matches("(?:SH|SZ|HK|US)\\..+")) {
            symbol = "US." + symbol;
        }
        return CanonicalQuote.normalizeSymbol(symbol);
    }
}
