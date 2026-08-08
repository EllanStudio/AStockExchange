package dev.astock.service.market;

import dev.astock.domain.quote.TradingStatus;
import dev.astock.service.security.SecurityInfo;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MockMarketDataProvider implements MarketDataProvider {
    private static final BigDecimal TICK = new BigDecimal("0.01");
    private final AtomicLong cycle = new AtomicLong();
    private final Clock clock;

    public MockMarketDataProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public List<ProviderQuote> fetchBulk(List<SecurityInfo> securities) {
        long currentCycle = cycle.incrementAndGet();
        return securities.stream().map(security -> quote(security, currentCycle)).toList();
    }

    @Override
    public Optional<ProviderQuote> fetchDetail(SecurityInfo security) {
        return Optional.of(quote(security, cycle.incrementAndGet()));
    }

    private ProviderQuote quote(SecurityInfo security, long currentCycle) {
        BigDecimal base = BigDecimal.valueOf(8 + Math.floorMod(security.symbol().hashCode(), 1_500) / 10.0)
                .setScale(2, RoundingMode.HALF_UP);
        double wave = Math.sin((currentCycle + security.id()) / 5.0) * 0.008;
        BigDecimal last = base.multiply(BigDecimal.valueOf(1.0 + wave)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal high = base.max(last).add(TICK.multiply(BigDecimal.valueOf(3)));
        BigDecimal low = base.min(last).subtract(TICK.multiply(BigDecimal.valueOf(3))).max(TICK);
        long bookVolume = 20_000L + Math.floorMod(currentCycle * 7919L + security.id(), 80_000L);
        long volume = currentCycle * 1_000_000L + security.id() * 10_000L;
        return new ProviderQuote(
                security.symbol(), security.name(), clock.millis(), last, base, base,
                high, low, last.subtract(TICK).max(TICK), bookVolume,
                last.add(TICK), bookVolume, volume, Math.multiplyExact(volume, last.longValue()),
                TradingStatus.TRADING, name()
        );
    }
}
