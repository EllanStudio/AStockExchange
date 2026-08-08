package dev.astock.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

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
            long gameCoinsPerCny,
            int baseSpreadBps,
            int additionalSlippageBps,
            int feeBps,
            long minimumFee,
            int marketOrderPriceCapBps
    ) {
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
