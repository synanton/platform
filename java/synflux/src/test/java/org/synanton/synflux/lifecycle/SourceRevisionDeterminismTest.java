package org.synanton.synflux.lifecycle;

import org.junit.jupiter.api.Test;
import org.synanton.synvault.domain.ContentRef;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The version id derives from the source revision, and the revision from the
 * ref's lastModified. Workflow checkpoints round-trip the Instant through
 * JSON at MILLISECOND precision — a recovery re-run sees a truncated mtime
 * while the pre-crash attempt saw full nanoseconds. With the old
 * Instant.toString() revision, the same source+bytes produced TWO version
 * ids across the crash boundary (M70q run 1: a chunk attributed to a version
 * id that was never inserted; the recovered run inserted a second id).
 * Millisecond epoch is stable across both paths.
 */
class SourceRevisionDeterminismTest {

    private static ContentRef ref(Instant lastModified) {
        return new ContentRef("file", "file:///fixture-doc.md", "text/markdown", 89, lastModified);
    }

    @Test
    void revisionIsIdenticalForFullNanosAndCheckpointTruncatedMillis() {
        Instant nanos = Instant.parse("2026-10-18T19:19:14.628764738Z");
        Instant checkpointMillis = Instant.ofEpochMilli(nanos.toEpochMilli());

        String fresh = FixtureIngestionService.sourceRevision(ref(nanos), 89, "digest");
        String recovery = FixtureIngestionService.sourceRevision(ref(checkpointMillis), 89, "digest");

        assertThat(recovery).isEqualTo(fresh);
    }

    @Test
    void revisionWithoutMtimeFallsBackToTheDigest() {
        assertThat(FixtureIngestionService.sourceRevision(ref(null), 89, "abc123"))
                .isEqualTo("sha256:abc123");
    }
}
