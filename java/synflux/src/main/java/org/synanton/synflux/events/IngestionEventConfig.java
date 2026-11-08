package org.synanton.synflux.events;

import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.synanton.eventing.contract.EventTypeDeclaration;
import org.synanton.eventing.contract.EventTypeRegistry;
import org.synanton.eventing.contract.ReplayClass;

/**
 * Registers the EWP-2 ingestion event types (Migration 3, step 3) so the shared
 * {@code EventEnvelopeBuilder}/{@code EventTypeRegistry} can validate the producer's
 * (type, schema-version, replay-class) at the boundary.
 */
@Configuration
public class IngestionEventConfig {

    public static final String SOURCE_PUBLISHED = "SourcePublished";
    public static final String SOURCE_DELETED = "SourceDeleted";
    public static final String EXTRACTION_REQUESTED = "ExtractionRequested";

    @Bean
    public EventTypeRegistry ingestionEventTypes() {
        return new EventTypeRegistry(List.of(
                new EventTypeDeclaration(SOURCE_PUBLISHED, 1, ReplayClass.REPLAY_SAFE, Optional.empty()),
                // Hazard H-1: a stale SourcePublished delivered after SourceDeleted can
                // resurrect the source; consumers must enforce per-source ordering.
                new EventTypeDeclaration(SOURCE_DELETED, 1, ReplayClass.REPLAY_SAFE, Optional.empty()),
                // Hazard H-2: a duplicate/retried LWT can re-emit this event.
                // Replay-safe and idempotent by (source identity, version_id).
                new EventTypeDeclaration(EXTRACTION_REQUESTED, 1, ReplayClass.REPLAY_SAFE, Optional.empty())));
    }
}
