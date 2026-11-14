package org.synanton.relix.consumer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.activation.SourceActivationStore;
import org.synanton.ingestioncache.claim.PromotionOwnershipStore;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.RelationPromotionResultRow;
import org.synanton.ingestioncache.domain.RelationPromotionWorkRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CanonicalGraphCompletionTest {

    private static final String TENANT = "tenant-a";
    private static final UUID VERSION = UUID.randomUUID();
    private final IngestionCacheClient cache = mock(IngestionCacheClient.class);
    private final SourceActivationStore activations = mock(SourceActivationStore.class);
    private final PromotionOwnershipStore ownership = mock(PromotionOwnershipStore.class);
    private final CanonicalGraphCompletion completion = new CanonicalGraphCompletion(
            cache, activations, Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC));
    private final CanonicalGraphCompletion ownershipGated = new CanonicalGraphCompletion(
            cache, activations, ownership,
            Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void zeroPromotionRowsAreTerminal() {
        when(cache.listRelationPromotionWorkRows(TENANT, VERSION)).thenReturn(List.of());

        assertThat(completion.promotionsTerminal(TENANT, VERSION)).isTrue();
    }

    @Test
    void openOwnershipBlocksCompletionEvenWhenPromotionRowsAreTerminal() {
        SourceVersionRow version = new SourceVersionRow(TENANT, "file", "source-a", "resource-a",
                VERSION, "digest", null, Instant.now(), null, null, "content-ref", 1,
                "digest", null);
        when(cache.listRelationPromotionWorkRows(TENANT, VERSION)).thenReturn(List.of());
        when(ownership.isClosed(TENANT, VERSION)).thenReturn(false);

        assertThat(ownershipGated.completeIfPromotionTerminal(version)).isFalse();
    }

    @Test
    void closedOwnershipAllowsCompletion() {
        SourceVersionRow version = new SourceVersionRow(TENANT, "file", "source-a", "resource-a",
                VERSION, "digest", null, Instant.now(), null, null, "content-ref", 1,
                "digest", null);
        when(cache.listRelationPromotionWorkRows(TENANT, VERSION)).thenReturn(List.of());
        when(ownership.isClosed(TENANT, VERSION)).thenReturn(true);
        when(cache.completeCanonicalGraphCommit(any(), any(), any(), any(), any(), any(), any(),
                any(), any())).thenReturn(true);

        assertThat(ownershipGated.completeIfPromotionTerminal(version)).isTrue();
    }

    @Test
    void completedAndRejectedPromotionsAuthorizeCompletion() {
        when(cache.listRelationPromotionWorkRows(TENANT, VERSION)).thenReturn(List.of(
                work("COMPLETED"), rejected()));

        assertThat(completion.promotionsTerminal(TENANT, VERSION)).isTrue();
    }

    @Test
    void unknownPromotionOutcomeBlocksCompletion() {
        when(cache.listRelationPromotionWorkRows(TENANT, VERSION)).thenReturn(List.of(
                work("COMPLETED"), unknown()));

        assertThat(completion.promotionsTerminal(TENANT, VERSION)).isFalse();
    }

    @Test
    void failedAndSupersededPromotionsBlockCompletion() {
        when(cache.listRelationPromotionWorkRows(TENANT, VERSION)).thenReturn(List.of(
                work("COMPLETED"), work("FAILED")));
        assertThat(completion.promotionsTerminal(TENANT, VERSION)).isFalse();

        when(cache.listRelationPromotionWorkRows(TENANT, VERSION)).thenReturn(List.of(
                work("SUPERSEDED")));
        assertThat(completion.promotionsTerminal(TENANT, VERSION)).isFalse();
    }

    @Test
    void pendingProcessingAndRetryablePromotionsAreNotTerminal() {
        when(cache.listRelationPromotionWorkRows(TENANT, VERSION)).thenReturn(List.of(
                work("COMPLETED"), work("PENDING")));

        assertThat(completion.promotionsTerminal(TENANT, VERSION)).isFalse();

        when(cache.listRelationPromotionWorkRows(TENANT, VERSION)).thenReturn(List.of(
                work("PROCESSING")));

        assertThat(completion.promotionsTerminal(TENANT, VERSION)).isFalse();
    }

    private static RelationPromotionWorkRow unknown() {
        return new RelationPromotionWorkRow(
                new RelationPromotionResultRow(TENANT, VERSION, "relation-unknown",
                        "mention-1", "mention-2", "requires", "UNKNOWN_OUTCOME", "entity-1", "entity-2",
                        "UNKNOWN_OUTCOME", "{}", "relation-validation-v1", "active", Instant.now(), null,
                        "source-a", VERSION, 1, "run-654", "CURRENT"),
                null, null, null, 0, null, null, null, null);
    }

    private static RelationPromotionWorkRow rejected() {
        return new RelationPromotionWorkRow(
                new RelationPromotionResultRow(TENANT, VERSION, "relation-rejected",
                        "mention-1", "mention-2", "requires", "INVALID", "entity-1", "entity-2",
                        "INVALID", "{}", "relation-validation-v1", "active", Instant.now(), null,
                        "source-a", VERSION, 1, "run-654", "CURRENT"),
                null, null, null, 0, null, null, null, null);
    }

    private static RelationPromotionWorkRow work(String claimStatus) {
        return new RelationPromotionWorkRow(
                new RelationPromotionResultRow(TENANT, VERSION, "relation-" + claimStatus,
                        "mention-1", "mention-2", "requires", "VALID", "entity-1", "entity-2",
                        "VALID", "{}", "relation-validation-v1", "active", Instant.now(), null,
                        "source-a", VERSION, 1, "run-654", "CURRENT"),
                claimStatus, null, null, 0, null, null, null, null);
    }
}
