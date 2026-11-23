package org.synanton.extraction.engine.provenance;

import java.util.Objects;
import org.synanton.extraction.engine.LlmProvenance;

/**
 * Replay policy for prompt provenance eras:
 *
 * <ul>
 *   <li>compiled + rendered: full replay; cache eligible after verification;</li>
 *   <li>rendered only: verification-only; never cache eligible;</li>
 *   <li>both null: historical; non-replayable unless explicitly verified against
 *       a trustworthy recorded rendered digest.</li>
 * </ul>
 *
 * <p>The policy never synthesizes a compiled digest from a rendered one.
 */
public final class PromptReplayPolicy {

    public enum Mode {
        FULL,
        VERIFICATION_ONLY,
        REJECTED
    }

    public record Decision(Mode mode, boolean cacheEligible, String reason) {}

    private PromptReplayPolicy() {}

    public static Decision decide(LlmProvenance provenance,
                                  String storedRenderedDigest,
                                  String currentRenderedDigest,
                                  boolean replayVerify) {
        Objects.requireNonNull(provenance, "provenance");
        String compiled = provenance.promptCompiledDigest();
        String rendered = provenance.promptRenderedDigest();
        if (compiled != null && rendered != null) {
            if (rendered.equals(currentRenderedDigest)) {
                return new Decision(Mode.FULL, true, "compiled and rendered digests verified");
            }
            return new Decision(Mode.REJECTED, false, "rendered digest mismatch");
        }
        if (compiled == null && rendered != null) {
            if (rendered.equals(currentRenderedDigest)) {
                return new Decision(Mode.VERIFICATION_ONLY, false,
                        "rendered-only era; verification-only, no cache identity");
            }
            return new Decision(Mode.REJECTED, false, "rendered-only digest mismatch");
        }
        if (compiled == null && rendered == null) {
            if (replayVerify && storedRenderedDigest != null
                    && storedRenderedDigest.equals(currentRenderedDigest)) {
                return new Decision(Mode.VERIFICATION_ONLY, false,
                        "historical null digest verified against recorded rendered digest");
            }
            return new Decision(Mode.REJECTED, false,
                    "historical null digest; explicit verification required");
        }
        return new Decision(Mode.REJECTED, false, "compiled digest without rendered digest");
    }
}
