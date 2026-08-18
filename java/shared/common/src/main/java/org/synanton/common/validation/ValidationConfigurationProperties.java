package org.synanton.common.validation;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "synapt.validation")
public class ValidationConfigurationProperties {

    private boolean strict = false;
    private int maxStringLengthHardCap = 65536;

    public boolean isStrict() {
        return strict;
    }

    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    public int getMaxStringLengthHardCap() {
        return maxStringLengthHardCap;
    }

    public void setMaxStringLengthHardCap(int maxStringLengthHardCap) {
        this.maxStringLengthHardCap = maxStringLengthHardCap;
    }

    public ValidationProperties toValidationProperties() {
        return new ValidationProperties(strict, maxStringLengthHardCap);
    }
}
