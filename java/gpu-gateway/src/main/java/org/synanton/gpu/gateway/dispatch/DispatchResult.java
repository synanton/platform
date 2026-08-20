package org.synanton.gpu.gateway.dispatch;

public record DispatchResult(
        boolean success,
        byte[] result,
        String errorMessage,
        long durationMs,
        long inputTokens,
        long outputTokens
) {
    public static DispatchResult ok(byte[] result, long durationMs, long inputTokens, long outputTokens) {
        return new DispatchResult(true, result, null, durationMs, inputTokens, outputTokens);
    }

    public static DispatchResult failed(String errorMessage) {
        return new DispatchResult(false, null, errorMessage, 0, 0, 0);
    }

    public static DispatchResult failed(String errorMessage, long durationMs) {
        return new DispatchResult(false, null, errorMessage, durationMs, 0, 0);
    }
}
