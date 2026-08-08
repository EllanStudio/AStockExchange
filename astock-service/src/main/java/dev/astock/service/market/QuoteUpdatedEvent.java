package dev.astock.service.market;

import dev.astock.domain.quote.CanonicalQuote;

public record QuoteUpdatedEvent(CanonicalQuote quote, boolean executable) {
}
