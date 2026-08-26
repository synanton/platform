package org.synanton.synflux.domain;

public record StageUsage(
    String name,
    long wallMs,
    long cpuNs,
    String modelId,
    long inputChars,
    long outputChars,
    int modelInputTokens,
    int modelOutputTokens,
    long inputBytes,
    long outputBytes
) {
    public static StageUsage of(String name, long wallMs, long cpuNs) {
        return new StageUsage(name, wallMs, cpuNs, null, 0, 0, 0, 0, 0, 0);
    }
}
