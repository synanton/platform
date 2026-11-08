package org.synanton.synquest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Versioned resolution policy thresholds. Scores are in the retrieval score
 * space (RRF for the current provider), not probabilities.
 */
@ConfigurationProperties(prefix = "resolution")
public record ResolutionPolicyProperties(
        String policyVersion,
        Double absoluteThreshold,
        Double marginThreshold) {

    public String policyVersionOrDefault() {
        return policyVersion == null || policyVersion.isBlank() ? "v1" : policyVersion;
    }

    public double absoluteThresholdOrDefault() {
        return absoluteThreshold == null ? 0.02 : absoluteThreshold;
    }

    public double marginThresholdOrDefault() {
        return marginThreshold == null ? 0.001 : marginThreshold;
    }
}
