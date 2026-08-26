package synanton.extraction.v1;

import java.util.Map;

/**
 * The contract-level error catalogue for {@code synanton.extraction.v1}, per proposal §24.
 *
 * <p>Each of the 13 codes maps to a retryability verdict and a recommended caller action. Callers
 * branch on {@link ExtractionErrorCode} and, where they need to decide whether to retry, on
 * {@link #isRetryable(ExtractionErrorCode)} - never on the {@code diagnostic} string, which is
 * unstable operator detail by design.
 *
 * <p>"Retryable" here means: retrying the identical request could plausibly succeed without the
 * caller changing anything. It does not promise success, and it does not mean retrying is free.
 * Retries of expensive extraction MUST reuse the original idempotency key (§13), or the retry
 * duplicates the work it was meant to avoid.
 */
public final class ExtractionErrorCatalogue {

    /**
     * Codes for which an unchanged retry could plausibly succeed.
     *
     * <p>Deliberately narrow. Capacity rejection and timeouts reflect transient conditions;
     * internal errors may be transient. Everything else describes a problem with the request or
     * the content, which retrying cannot fix - retrying those just burns extraction capacity.
     */
    private static final Map<ExtractionErrorCode, Boolean> RETRYABLE = Map.ofEntries(
            Map.entry(ExtractionErrorCode.ERROR_INVALID_REQUEST, false),
            Map.entry(ExtractionErrorCode.ERROR_INVALID_OBJECT_REFERENCE, false),
            Map.entry(ExtractionErrorCode.ERROR_OBJECT_NOT_FOUND, false),
            Map.entry(ExtractionErrorCode.ERROR_OBJECT_CHANGED, false),
            Map.entry(ExtractionErrorCode.ERROR_UNSUPPORTED_MEDIA_TYPE, false),
            Map.entry(ExtractionErrorCode.ERROR_UNSUPPORTED_OPTION, false),
            Map.entry(ExtractionErrorCode.ERROR_REJECTED_CAPACITY, true),
            Map.entry(ExtractionErrorCode.ERROR_EXPIRED, false),
            Map.entry(ExtractionErrorCode.ERROR_TIMEOUT, true),
            Map.entry(ExtractionErrorCode.ERROR_EXTRACTION_FAILED, false),
            Map.entry(ExtractionErrorCode.ERROR_PARTIAL_EXTRACTION, false),
            Map.entry(ExtractionErrorCode.ERROR_PAYLOAD_INVALID, false),
            Map.entry(ExtractionErrorCode.ERROR_INTERNAL_ERROR, true));

    /** Recommended caller action per code. Documentation, not control flow. */
    private static final Map<ExtractionErrorCode, String> CALLER_ACTION = Map.ofEntries(
            Map.entry(ExtractionErrorCode.ERROR_INVALID_REQUEST,
                    "Fix the request. Also returned when an idempotency key is reused with "
                            + "materially different parameters."),
            Map.entry(ExtractionErrorCode.ERROR_INVALID_OBJECT_REFERENCE,
                    "Fix the object reference: bucket, key, sha256 format, or size."),
            Map.entry(ExtractionErrorCode.ERROR_OBJECT_NOT_FOUND,
                    "Confirm the object exists and the plane can read it. Do not retry unchanged."),
            Map.entry(ExtractionErrorCode.ERROR_OBJECT_CHANGED,
                    "The object no longer matches the supplied sha256. Re-read the source and "
                            + "submit a new request with the current digest."),
            Map.entry(ExtractionErrorCode.ERROR_UNSUPPORTED_MEDIA_TYPE,
                    "This plane cannot process the media type. Consult GetCapabilities; apply the "
                            + "caller's own fallback policy."),
            Map.entry(ExtractionErrorCode.ERROR_UNSUPPORTED_OPTION,
                    "Drop or change the option. Prefer inspecting feature_states, which reports "
                            + "unsupported features without failing the whole operation."),
            Map.entry(ExtractionErrorCode.ERROR_REJECTED_CAPACITY,
                    "Back off and retry with the SAME idempotency key. GetCapacity is advisory."),
            Map.entry(ExtractionErrorCode.ERROR_EXPIRED,
                    "expires_at passed. This is a lifecycle outcome, not a technical failure. "
                            + "Resubmit with a new expiry if the result is still wanted."),
            Map.entry(ExtractionErrorCode.ERROR_TIMEOUT,
                    "Processing exceeded the plane's ceiling. Retry with the same key, or split "
                            + "the artifact."),
            Map.entry(ExtractionErrorCode.ERROR_EXTRACTION_FAILED,
                    "The content could not be processed. Inspect the diagnostic; do not loop."),
            Map.entry(ExtractionErrorCode.ERROR_PARTIAL_EXTRACTION,
                    "Some content was extracted. Inspect feature_states and per-item status, and "
                            + "decide whether the partial result is usable."),
            Map.entry(ExtractionErrorCode.ERROR_PAYLOAD_INVALID,
                    "The plane produced a payload that failed its own validation. Report it: this "
                            + "is a plane defect, not a caller error."),
            Map.entry(ExtractionErrorCode.ERROR_INTERNAL_ERROR,
                    "Unclassified fault. Retry once with the same key; escalate if it persists."));

    private ExtractionErrorCatalogue() {
    }

    /**
     * Whether an unchanged retry could plausibly succeed.
     *
     * <p>Unknown and unspecified codes are treated as NOT retryable: a client meeting a code it
     * does not recognise must not decide on its own that looping is safe.
     */
    public static boolean isRetryable(ExtractionErrorCode code) {
        return RETRYABLE.getOrDefault(code, false);
    }

    /** Recommended caller action, or a safe default for unknown codes. */
    public static String callerAction(ExtractionErrorCode code) {
        return CALLER_ACTION.getOrDefault(
                code, "Unrecognised error code. Do not retry automatically; escalate.");
    }

    /** Every code with a documented action. Used by the contract test to assert completeness. */
    public static Map<ExtractionErrorCode, String> documentedActions() {
        return CALLER_ACTION;
    }

    /** Every code with a retryability verdict. */
    public static Map<ExtractionErrorCode, Boolean> retryability() {
        return RETRYABLE;
    }
}
