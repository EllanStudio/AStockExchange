package dev.astock.domain.quote;

import java.util.List;
import java.util.Objects;

public record QuoteAssessment(
        boolean acceptedForDisplay,
        boolean executable,
        CanonicalQuote quote,
        List<String> violations
) {
    public QuoteAssessment {
        quote = Objects.requireNonNull(quote, "quote");
        violations = List.copyOf(violations);
    }
}
