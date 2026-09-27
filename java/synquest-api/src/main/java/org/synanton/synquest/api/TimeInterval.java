package org.synanton.synquest.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Closed validity interval {@code [validFrom, validTo]} (Design 1.34 validity time).
 */
public record TimeInterval(Instant validFrom, Instant validTo) {
    public TimeInterval {
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(validTo, "validTo");
        if (validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("validTo must not precede validFrom");
        }
    }
}
