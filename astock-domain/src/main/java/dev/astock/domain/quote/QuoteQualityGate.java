package dev.astock.domain.quote;

import java.time.Clock;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stateful quote validation. A rejected sample never replaces the last accepted
 * sample, which prevents a bad source response from poisoning monotonic checks.
 */
public final class QuoteQualityGate {
    private final QuoteQualityPolicy policy;
    private final Clock clock;
    private final Map<Integer, CanonicalQuote> lastAccepted = new ConcurrentHashMap<>();

    public QuoteQualityGate(QuoteQualityPolicy policy, Clock clock) {
        this.policy = policy;
        this.clock = clock;
    }

    public QuoteAssessment assess(CanonicalQuote candidate, int priceLimitBps) {
        return assess(candidate, priceLimitBps, Optional.empty());
    }

    public QuoteAssessment assess(
            CanonicalQuote candidate,
            int priceLimitBps,
            Optional<CanonicalQuote> comparisonSource
    ) {
        var fatal = new ArrayList<String>();
        var degraded = new ArrayList<String>();
        CanonicalQuote previous = lastAccepted.get(candidate.securityId());

        if (candidate.lastPrice() <= 0) fatal.add("last price is not positive");
        if (candidate.previousClose() <= 0) fatal.add("previous close is not positive");
        if (candidate.highPrice() > 0 && candidate.highPrice() < candidate.lastPrice()) {
            fatal.add("high price is below last price");
        }
        if (candidate.lowPrice() > 0 && candidate.lowPrice() > candidate.lastPrice()) {
            fatal.add("low price is above last price");
        }
        if (candidate.volume() < 0 || candidate.turnover() < 0) fatal.add("volume or turnover is negative");
        if (candidate.bid1Price() < 0 || candidate.ask1Price() < 0
                || candidate.bid1Volume() < 0 || candidate.ask1Volume() < 0) {
            fatal.add("order book contains a negative value");
        }
        if (candidate.bid1Price() > 0 && candidate.ask1Price() > 0
                && candidate.bid1Price() > candidate.ask1Price()) {
            fatal.add("crossed order book");
        }

        if (previous != null) {
            if (candidate.sequence() <= previous.sequence()) fatal.add("sequence did not advance");
            if (candidate.sourceTimestamp() < previous.sourceTimestamp()) fatal.add("source timestamp moved backwards");
            if (sameTradingDay(previous.sourceTimestamp(), candidate.sourceTimestamp())
                    && candidate.volume() < previous.volume()) {
                fatal.add("intraday volume decreased");
            }
        }

        if (candidate.previousClose() > 0 && priceLimitBps > 0) {
            long observedBps = differenceBps(candidate.lastPrice(), candidate.previousClose());
            if (observedBps > (long) priceLimitBps + policy.priceLimitToleranceBps()) {
                fatal.add("price is outside configured daily limit");
            }
        }

        boolean sourceConflict = comparisonSource
                .filter(peer -> peer.lastPrice() > 0)
                .map(peer -> differenceBps(candidate.lastPrice(), peer.lastPrice()) > policy.sourceDivergenceBps())
                .orElse(false);
        if (sourceConflict) fatal.add("primary and fallback sources diverge");

        long ageMillis = Math.max(0, clock.millis() - candidate.sourceTimestamp());
        if (ageMillis > policy.displayStaleAfter().toMillis()) {
            fatal.add("quote is too stale for display");
        } else if (ageMillis > policy.executionStaleAfter().toMillis()) {
            degraded.add("quote is too stale for execution");
        }
        if (!candidate.hasExecutableBook()) degraded.add("best bid or ask is unavailable");
        if (candidate.status() != TradingStatus.TRADING) degraded.add("security is not trading");

        if (!fatal.isEmpty()) {
            var all = new ArrayList<>(fatal);
            all.addAll(degraded);
            if (sourceConflict && fatal.size() == 1) {
                // Keep the conflicting sample visible for operators/players while
                // making the symbol fail closed for every execution path.
                return new QuoteAssessment(true, false, candidate.withQuality(QuoteQuality.CONFLICT), all);
            }
            return new QuoteAssessment(false, false, candidate.withQuality(QuoteQuality.REJECTED), all);
        }

        QuoteQuality quality = degraded.isEmpty() ? QuoteQuality.VALID : QuoteQuality.DEGRADED;
        CanonicalQuote accepted = candidate.withQuality(quality);
        lastAccepted.put(candidate.securityId(), accepted);
        return new QuoteAssessment(true, degraded.isEmpty(), accepted, degraded);
    }

    public void clear(int securityId) {
        lastAccepted.remove(securityId);
    }

    private static long differenceBps(long a, long b) {
        if (b <= 0) return Long.MAX_VALUE;
        BigInteger numerator = BigInteger.valueOf(a).subtract(BigInteger.valueOf(b)).abs()
                .multiply(BigInteger.valueOf(10_000));
        BigInteger denominator = BigInteger.valueOf(b);
        BigInteger[] parts = numerator.divideAndRemainder(denominator);
        BigInteger rounded = parts[0];
        if (parts[1].shiftLeft(1).compareTo(denominator) >= 0) rounded = rounded.add(BigInteger.ONE);
        return rounded.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0 ? Long.MAX_VALUE : rounded.longValue();
    }

    private static boolean sameTradingDay(long left, long right) {
        // A rollback larger than 12 hours is considered a new session. Calendar-aware
        // reset is also performed by the service at settlement time.
        return right - left < 43_200_000L;
    }
}
