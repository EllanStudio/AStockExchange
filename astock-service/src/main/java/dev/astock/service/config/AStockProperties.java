package dev.astock.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@ConfigurationProperties("astock")
public record AStockProperties(
        Api api,
        Market market,
        Data data,
        Pricing pricing,
        Economy economy,
        Risk risk
) {
    public record Api(String key) {
    }

    public record Market(String timezone, boolean enforceSessions, Duration quoteEpoch) {
    }

    public record Data(
            String primary,
            String fallback,
            Duration bulkRefresh,
            Duration detailSingleFlight,
            Duration connectTimeout,
            Duration requestTimeout,
            Duration executionStaleAfter,
            Duration displayStaleAfter,
            int sourceDivergenceBps,
            int consecutiveErrorBreaker
    ) {
    }

    public record Pricing(
            Map<String, Long> gameCoinsPerCurrencyUnit,
            int baseSpreadBps,
            int additionalSlippageBps,
            int feeBps,
            long minimumFee,
            int marketOrderPriceCapBps
    ) {
        public Pricing {
            if (gameCoinsPerCurrencyUnit == null || gameCoinsPerCurrencyUnit.isEmpty()) {
                throw new IllegalArgumentException("pricing currency rates are required");
            }
            Map<String, Long> normalized = new LinkedHashMap<>();
            gameCoinsPerCurrencyUnit.forEach((currency, rate) -> {
                String code = Objects.requireNonNull(currency, "currency").trim().toUpperCase(Locale.ROOT);
                if (!code.matches("[A-Z]{3}") || rate == null || rate <= 0) {
                    throw new IllegalArgumentException("invalid game-economy rate for " + currency);
                }
                normalized.put(code, rate);
            });
            for (String required : new String[]{"CNY", "HKD", "USD"}) {
                if (!normalized.containsKey(required)) {
                    throw new IllegalArgumentException("missing game-economy rate for " + required);
                }
            }
            gameCoinsPerCurrencyUnit = Map.copyOf(normalized);
        }

        public long gameCoinsPerUnit(String currency) {
            String code = Objects.requireNonNull(currency, "currency").trim().toUpperCase(Locale.ROOT);
            Long rate = gameCoinsPerCurrencyUnit.get(code);
            if (rate == null) throw new IllegalArgumentException("no game-economy rate for " + code);
            return rate;
        }
    }

    public record Economy(long maxTransferAmount) {
    }

    public record Risk(Player player, MarketMaker marketMaker, Trading trading) {
        public record Player(
                int maxOpenOrders,
                long maxDailyTurnover,
                int maxSingleSymbolPercent,
                int maxTotalStockPercent
        ) {
        }

        public record MarketMaker(
                int minimumCoverageRatioBps,
                int stopNewBuyRatioBps,
                long maxSymbolExposure,
                long maxGlobalExposure
        ) {
        }

        public record Trading(
                boolean shortSelling,
                boolean leverage,
                long marketOrderMaxNotional
        ) {
        }
    }
}
