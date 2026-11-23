package org.synanton.extraction.engine.capture;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.LlmUsage;
import org.synanton.extraction.engine.QuarantinedLine;
import org.synanton.extraction.engine.TextSegment;

/**
 * #830 diagnostic capture boundary. Capture is write-only by default and
 * diagnostic-only; production enablement remains gated and is not authorized by
 * this interface.
 */
public interface CaptureStore {

    void writeQuarantine(ExtractionContext context, TextSegment segment, LlmUsage usage,
                         List<QuarantinedLine> lines);

    void writeParseFailure(ExtractionContext context, TextSegment segment, LlmUsage usage,
                           String rawBody, String content, String parserInput,
                           RuntimeException failure);

    /** Idempotent source-version cleanup; must not touch another tenant/version. */
    void deleteSource(String tenantId, String sourceVersionId);

    /** Deletes capture files older than the cutoff. Returns the number removed. */
    int expireOlderThan(Instant cutoff);

    Optional<Path> root();
}
