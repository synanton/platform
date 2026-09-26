package org.synanton.synquest.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.synanton.storage.contract.SourceVersionId;
import org.synanton.storage.contract.VersionSeriesId;

/**
 * Temporal pre-ranking constraints (Design 1.34 extension point, proposal §10.2).
 * Empty by default in this PoC ("current-only"); populated by a later temporal PoC
 * without changing the {@link SynquestEngine} port shape.
 *
 * <p>Adapters with {@code capabilities().temporal() == false} must reject a non-empty
 * instance at query time rather than silently ignoring it.
 */
public record TemporalExtension(
        Optional<Instant> asOfPublication,
        Optional<Instant> asOfObservation,
        Optional<TimeInterval> validAt,
        Optional<VersionSeriesId> versionSeriesId,
        Optional<SourceVersionId> sourceVersionId,
        boolean includeAllEligibleVersions) {
    public TemporalExtension {
        Objects.requireNonNull(asOfPublication, "asOfPublication");
        Objects.requireNonNull(asOfObservation, "asOfObservation");
        Objects.requireNonNull(validAt, "validAt");
        Objects.requireNonNull(versionSeriesId, "versionSeriesId");
        Objects.requireNonNull(sourceVersionId, "sourceVersionId");
    }

    public static TemporalExtension empty() {
        return new TemporalExtension(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), false);
    }

    public boolean isEmpty() {
        return asOfPublication.isEmpty()
                && asOfObservation.isEmpty()
                && validAt.isEmpty()
                && versionSeriesId.isEmpty()
                && sourceVersionId.isEmpty()
                && !includeAllEligibleVersions;
    }
}
