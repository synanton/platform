package org.synanton.gpu.client;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.synanton.gpu.v1.ErrorReason;
import org.synanton.gpu.v1.ExecutionResponse;

/**
 * Canonical GPU-plane error codes (Deployment Plan §16).
 *
 * <p>Pre-execution denials carry the code in the {@code x-synanton-error-code} trailer.
 * Failed executions carry it in {@code ErrorInfo.code}.
 */
public final class GpuErrorCodes {

    public static final Metadata.Key<String> ERROR_CODE_KEY =
            Metadata.Key.of("x-synanton-error-code", Metadata.ASCII_STRING_MARSHALLER);

    private GpuErrorCodes() {}

    /** Trailer code, e.g. {@code tenant_not_allowed}; else {@code status_<grpc code>}. */
    public static String canonicalCode(StatusRuntimeException e) {
        Metadata trailers = e.getTrailers();
        String code = trailers == null ? null : trailers.get(ERROR_CODE_KEY);
        return code != null ? code : "status_" + e.getStatus().getCode().name().toLowerCase();
    }

    /** {@code ErrorInfo.code} of a failed execution, or null. */
    public static String canonicalCode(ExecutionResponse response) {
        return response.hasError() && !response.getError().getCode().isEmpty()
                ? response.getError().getCode() : null;
    }

    /**
     * Whether a pre-execution denial may succeed on retry. Only capacity-type denials are
     * transient. Security, policy and validation denials (tenant_not_allowed,
     * budget_exceeded, sensitive_model_external_blocked, …) never succeed on retry. A legacy
     * gateway without the trailer keeps the old behaviour (retryable).
     */
    public static boolean isRetryableDenial(StatusRuntimeException e) {
        return switch (canonicalCode(e)) {
            case "concurrency_limit_reached", "capacity_exceeded" -> true;
            default -> e.getTrailers() == null || e.getTrailers().get(ERROR_CODE_KEY) == null;
        };
    }

    /**
     * Stricter than {@link #isRetryableDenial} for fail-closed callers. A transport-level
     * {@code UNAVAILABLE} without a canonical code is transient, and so are capacity denials.
     * Anything with a canonical policy code is not: {@code circuit_open}, {@code routing_disabled},
     * {@code budget_exceeded}, …
     */
    public static boolean isTransient(StatusRuntimeException e) {
        boolean coded = e.getTrailers() != null && e.getTrailers().get(ERROR_CODE_KEY) != null;
        if (coded) {
            return isRetryableDenial(e);
        }
        return e.getStatus().getCode() == Status.Code.UNAVAILABLE;
    }

    /** A failed execution that may succeed on retry: retryable MODEL_NOT_READY / capacity. */
    public static boolean isTransient(ExecutionResponse response) {
        if (!response.hasError() || !response.getError().getRetryable()) {
            return false;
        }
        ErrorReason r = response.getError().getReason();
        return r == ErrorReason.MODEL_NOT_READY || r == ErrorReason.GPU_CAPACITY_EXCEEDED;
    }
}
