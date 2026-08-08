package dev.astock.service.market;

import dev.astock.service.security.SecurityInfo;

import java.util.List;
import java.util.Optional;

public interface MarketDataProvider {
    String name();

    List<ProviderQuote> fetchBulk(List<SecurityInfo> securities) throws MarketDataException;

    Optional<ProviderQuote> fetchDetail(SecurityInfo security) throws MarketDataException;
}
