package org.synanton.common.resolution;

/**
 * Maps caught failures to exactly one {@link FailureClass}. Unexpected
 * exceptions are contract violations, never silent infrastructure retries.
 */
public final class FailureClassifier {

    private FailureClassifier() {}

    public static FailureClass classify(Throwable failure) {
        if (failure instanceof StaleResolutionException) {
            return FailureClass.STALE_REJECT;
        }
        if (failure instanceof ContractViolationException
                || failure instanceof IllegalArgumentException
                || failure instanceof NullPointerException) {
            return FailureClass.CONTRACT_VIOLATION;
        }
        if (failure instanceof RetryableProcessingException) {
            return FailureClass.INFRASTRUCTURE_RETRYABLE;
        }
        return FailureClass.CONTRACT_VIOLATION;
    }
}
