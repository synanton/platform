package org.synanton.synvault.api;

import java.util.Objects;
import org.synanton.storage.contract.Provisional;

/**
 * Durable publication-record payload committed atomically with a document revision and
 * later handed to Eventing 1.27 by the adapter-internal relay.
 *
 * <p>{@link Provisional} {@code 1.27}: the event payload shape binds to the Design 1.27
 * event schema at freeze and may change with no deprecation obligation (YDB-POC-010).
 *
 * @param revisionId publication-record identity (also the Synvault commit-sequence key)
 * @param tenantId   owning tenant (relay consumption is tenant-scoped)
 * @param payloadJson event payload destined for Eventing 1.27
 */
@Provisional(value = "1.27", reason = "Event payload shape binds to the 1.27 event schema at freeze")
public record PublicationIntent(String revisionId, String tenantId, String payloadJson) {
    public PublicationIntent {
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(payloadJson, "payloadJson");
        if (revisionId.isBlank() || tenantId.isBlank()) {
            throw new IllegalArgumentException("revisionId and tenantId must not be blank");
        }
    }
}
