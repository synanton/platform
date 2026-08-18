package org.synanton.common.validation;

/**
 * Validation rollout flags consumed by {@link ValidationExceptionHandler}.
 */
public record ValidationProperties(boolean strict, int maxStringLengthHardCap) {
    public ValidationProperties {
        if (maxStringLengthHardCap <= 0) {
            maxStringLengthHardCap = 65536;
        }
    }
}
