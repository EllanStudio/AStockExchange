package dev.astock.service.trading;

import dev.astock.domain.money.ScaledMath;
import dev.astock.domain.order.OrderSide;
import dev.astock.domain.quote.CanonicalQuote;
import dev.astock.service.config.AStockProperties;
import dev.astock.service.market.QuoteCoordinator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Service
public class RiskService {
    private final JdbcTemplate jdbc;
    private final QuoteCoordinator quotes;
    private final AStockProperties properties;

    public RiskService(JdbcTemplate jdbc, QuoteCoordinator quotes, AStockProperties properties) {
        this.jdbc = jdbc;
        this.quotes = quotes;
        this.properties = properties;
    }

    public void checkPlacement(long accountId, int securityId, String symbol, String currency,
                               OrderSide side, long proposedNotional, LocalDate tradeDate) {
        Integer openOrders = jdbc.queryForObject("""
                SELECT COUNT(*) FROM astock_order
                WHERE account_id = ? AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                """, Integer.class, accountId);
        if (openOrders != null && openOrders >= properties.risk().player().maxOpenOrders()) {
            throw new TradeRejectedException("MAX_OPEN_ORDERS", "open order limit reached");
        }
        Long daily = jdbc.queryForObject("""
                SELECT COALESCE(SUM(f.notional), 0) FROM astock_fill f
                JOIN astock_order o ON o.order_id = f.order_id
                WHERE o.account_id = ?
                  AND COALESCE(f.trade_date, CAST(f.created_at AS DATE)) = ?
                """, Long.class, accountId, Date.valueOf(tradeDate));
        if (daily != null && Math.addExact(daily, proposedNotional) > properties.risk().player().maxDailyTurnover()) {
            throw new TradeRejectedException("MAX_DAILY_TURNOVER", "daily turnover limit exceeded");
        }
        if (side == OrderSide.BUY) {
            checkMarketMakerCoverage(securityId, symbol, currency, proposedNotional);
            checkConcentration(accountId, securityId, symbol, proposedNotional);
        }
    }

    public TreasurySnapshot treasury() {
        long treasury = systemBalance("MARKET_MAKER");
        Map<Integer, CanonicalQuote> byId = new HashMap<>();
        quotes.snapshot().forEach(quote -> byId.put(quote.securityId(), quote));
        Long liability = jdbc.query("""
                SELECT p.security_id, s.currency, SUM(p.quantity_total) AS quantity
                FROM astock_position p
                JOIN astock_security s ON s.security_id = p.security_id
                GROUP BY p.security_id, s.currency
                """, result -> {
            long total = 0;
            while (result.next()) {
                CanonicalQuote quote = byId.get(result.getInt("security_id"));
                long anchor = quote == null ? 0
                        : quote.bid1Price() > 0 ? quote.bid1Price() : quote.lastPrice();
                if (anchor > 0) {
                    long price = ScaledMath.subtractBps(anchor, properties.pricing().baseSpreadBps());
                    total = Math.addExact(total, ScaledMath.notionalCash(
                            result.getLong("quantity"), price,
                            properties.pricing().gameCoinsPerUnit(result.getString("currency"))));
                }
            }
            return total;
        });
        long safeLiability = liability == null ? 0 : liability;
        int coverage = safeLiability == 0 ? Integer.MAX_VALUE : ratioBps(treasury, safeLiability);
        return new TreasurySnapshot(treasury, safeLiability, coverage,
                coverage >= properties.risk().marketMaker().stopNewBuyRatioBps());
    }

    private void checkMarketMakerCoverage(int securityId, String symbol, String currency,
                                          long proposedNotional) {
        TreasurySnapshot snapshot = treasury();
        if (!snapshot.acceptingNpcBuys()) {
            throw new TradeRejectedException("TREASURY_COVERAGE", "market maker coverage is below the buy threshold");
        }
        if (snapshot.liability() > properties.risk().marketMaker().maxGlobalExposure()) {
            throw new TradeRejectedException("GLOBAL_EXPOSURE", "global market maker exposure limit reached");
        }
        Long quantity = jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity_total), 0) FROM astock_position WHERE security_id = ?
                """, Long.class, securityId);
        CanonicalQuote quote = quotes.current(symbol).orElse(null);
        long symbolExposure = 0;
        if (quote != null && quantity != null && quantity > 0) {
            symbolExposure = ScaledMath.notionalCash(quantity,
                    quote.bid1Price() > 0 ? quote.bid1Price() : quote.lastPrice(),
                    properties.pricing().gameCoinsPerUnit(currency));
        }
        if (Math.addExact(symbolExposure, proposedNotional)
                > properties.risk().marketMaker().maxSymbolExposure()) {
            throw new TradeRejectedException("SYMBOL_EXPOSURE", "market maker symbol exposure limit reached");
        }
    }

    private void checkConcentration(long accountId, int securityId, String symbol, long proposedNotional) {
        long cash = jdbc.queryForObject("""
                SELECT cash_available + cash_frozen FROM astock_account WHERE account_id = ?
                """, Long.class, accountId);
        long stockValue = 0;
        long symbolValue = 0;
        for (var position : jdbc.queryForList("""
                SELECT p.security_id, p.quantity_total, s.currency
                FROM astock_position p
                JOIN astock_security s ON s.security_id = p.security_id
                WHERE p.account_id = ? AND p.quantity_total > 0
                """, accountId)) {
            int id = ((Number) position.get("security_id")).intValue();
            long quantity = ((Number) position.get("quantity_total")).longValue();
            CanonicalQuote quote = quotes.snapshot().stream()
                    .filter(value -> value.securityId() == id).findFirst().orElse(null);
            if (quote != null) {
                long value = ScaledMath.notionalCash(quantity,
                        quote.bid1Price() > 0 ? quote.bid1Price() : quote.lastPrice(),
                        properties.pricing().gameCoinsPerUnit(position.get("currency").toString()));
                stockValue = Math.addExact(stockValue, value);
                if (id == securityId) symbolValue = Math.addExact(symbolValue, value);
            }
        }
        Long pending = jdbc.queryForObject("""
                SELECT COALESCE(SUM(reserved_cash), 0) FROM astock_order
                WHERE account_id = ? AND side = 'BUY' AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                """, Long.class, accountId);
        Long pendingSymbol = jdbc.queryForObject("""
                SELECT COALESCE(SUM(reserved_cash), 0) FROM astock_order
                WHERE account_id = ? AND security_id = ? AND side = 'BUY'
                  AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                """, Long.class, accountId, securityId);
        long pendingValue = pending == null ? 0 : pending;
        long pendingSymbolValue = pendingSymbol == null ? 0 : pendingSymbol;
        long equity = Math.addExact(cash, stockValue);
        if (equity <= 0) throw new TradeRejectedException("NO_EQUITY", "account has no equity");
        long futureStock = Math.addExact(Math.addExact(stockValue, pendingValue), proposedNotional);
        long futureSymbol = Math.addExact(Math.addExact(symbolValue, pendingSymbolValue), proposedNotional);
        if (exceedsRatio(futureSymbol, equity, properties.risk().player().maxSingleSymbolPercent())) {
            throw new TradeRejectedException("SYMBOL_CONCENTRATION", symbol + " exceeds the per-symbol allocation limit");
        }
        if (exceedsRatio(futureStock, equity, properties.risk().player().maxTotalStockPercent())) {
            throw new TradeRejectedException("STOCK_ALLOCATION", "total stock allocation limit exceeded");
        }
    }

    private long systemBalance(String code) {
        Long value = jdbc.queryForObject("SELECT cash_balance FROM astock_system_account WHERE account_code = ?",
                Long.class, code);
        if (value == null) throw new IllegalStateException("missing system account " + code);
        return value;
    }

    private static int ratioBps(long numerator, long denominator) {
        BigInteger ratio = BigInteger.valueOf(numerator).multiply(BigInteger.valueOf(10_000))
                .divide(BigInteger.valueOf(denominator));
        return ratio.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0
                ? Integer.MAX_VALUE : ratio.intValueExact();
    }

    private static boolean exceedsRatio(long numerator, long denominator, int maximumBps) {
        return BigInteger.valueOf(numerator).multiply(BigInteger.valueOf(10_000))
                .compareTo(BigInteger.valueOf(denominator).multiply(BigInteger.valueOf(maximumBps))) > 0;
    }

    public record TreasurySnapshot(
            long treasuryBalance,
            long liability,
            int coverageRatioBps,
            boolean acceptingNpcBuys
    ) {
    }
}
