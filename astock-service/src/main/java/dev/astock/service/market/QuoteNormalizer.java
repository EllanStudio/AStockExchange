package dev.astock.service.market;

import dev.astock.domain.money.ScaledMath;
import dev.astock.domain.quote.CanonicalQuote;
import dev.astock.domain.quote.QuoteQuality;
import dev.astock.service.security.SecurityInfo;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class QuoteNormalizer {
    public CanonicalQuote normalize(SecurityInfo security, ProviderQuote raw, long sequence, long receivedTimestamp) {
        return new CanonicalQuote(
                security.id(), security.symbol(), sequence, raw.sourceTimestamp(), receivedTimestamp,
                price(raw.lastPrice()), price(raw.previousClose()), price(raw.openPrice()),
                price(raw.highPrice()), price(raw.lowPrice()), price(raw.bid1Price()), raw.bid1Volume(),
                price(raw.ask1Price()), raw.ask1Volume(), raw.volume(), raw.turnover(), raw.status(),
                QuoteQuality.VALID, raw.source()
        );
    }

    private static long price(BigDecimal value) {
        return value == null || value.signum() <= 0 ? 0 : ScaledMath.priceUnits(value);
    }
}
