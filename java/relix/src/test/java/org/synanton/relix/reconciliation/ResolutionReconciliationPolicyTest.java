package org.synanton.relix.reconciliation;

import org.synanton.common.resolution.GraphResolutionObservation;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synanton.common.resolution.ResolutionDecision;
import org.synanton.common.resolution.ResolutionOutcome;
import org.synanton.ingestioncache.domain.ResolutionResultRow;
import org.synanton.ingestioncache.domain.ResolutionWorkRow;
import org.synanton.relix.graph.ResolutionIds;

import static org.assertj.core.api.Assertions.assertThat;

class ResolutionReconciliationPolicyTest {

    private static final String TENANT = "tenant-reconcile";
    private static final UUID VERSION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String MENTION = "mention-1";
    private static final String ENTITY = "entity-1";
    private static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");

    private final ResolutionReconciliationPolicy policy = new ResolutionReconciliationPolicy();

    @Test
    void completedResolvedWithNoGraphHistoryRepairsAtStoredFence() {
        ReconciliationAction action = policy.decide(
                work("COMPLETED", 3, NOW.plusSeconds(300)),
                result("RESOLVED", ENTITY), Optional.empty(), false, NOW);

        assertThat(action.kind()).isEqualTo(ReconciliationKind.REPAIR_GRAPH);
        assertThat(action.resolutionId()).isEqualTo(resolutionId(ENTITY, "v1"));
        assertThat(action.resolutionDecision().fencingToken()).isEqualTo(3);
    }

    @Test
    void completedResolvedWithExactActiveEdgeIsNoop() {
        ReconciliationAction action = policy.decide(
                work("COMPLETED", 3, NOW.plusSeconds(300)),
                result("RESOLVED", ENTITY),
                Optional.of(state(ENTITY, "v1", 3)), true, NOW);

        assertThat(action.kind()).isEqualTo(ReconciliationKind.NOOP);
    }

    @Test
    void completedResolvedWithOlderGraphFenceIsRepairedUpward() {
        ReconciliationAction action = policy.decide(
                work("COMPLETED", 3, NOW.plusSeconds(300)),
                result("RESOLVED", ENTITY),
                Optional.of(state(ENTITY, "v1", 2)), true, NOW);

        assertThat(action.kind()).isEqualTo(ReconciliationKind.REPAIR_GRAPH);
        assertThat(action.resolutionDecision().fencingToken()).isEqualTo(3);
    }

    @Test
    void completedResolvedWithDifferentEntityIsContradiction() {
        ReconciliationAction action = policy.decide(
                work("COMPLETED", 3, NOW.plusSeconds(300)),
                result("RESOLVED", ENTITY),
                Optional.of(state("entity-other", "v1", 3)), true, NOW);

        assertThat(action.kind()).isEqualTo(ReconciliationKind.CONTRADICTION);
        assertThat(action.resolutionDecision()).isNull();
    }

    @Test
    void completedResolvedWithNewerGraphFenceIsContradiction() {
        ReconciliationAction action = policy.decide(
                work("COMPLETED", 3, NOW.plusSeconds(300)),
                result("RESOLVED", ENTITY),
                Optional.of(state(ENTITY, "v1", 4)), true, NOW);

        assertThat(action.kind()).isEqualTo(ReconciliationKind.CONTRADICTION);
    }

    @Test
    void completedResolvedWithOnlyHistoryIsContradiction() {
        ReconciliationAction action = policy.decide(
                work("COMPLETED", 3, NOW.plusSeconds(300)),
                result("RESOLVED", ENTITY), Optional.empty(), true, NOW);

        assertThat(action.kind()).isEqualTo(ReconciliationKind.CONTRADICTION);
    }

    @Test
    void completedNonResolvedWithActiveEdgeIsDetectedButNotMutated() {
        ReconciliationAction action = policy.decide(
                work("COMPLETED", 3, NOW.plusSeconds(300)),
                result("UNRESOLVED", null),
                Optional.of(state(ENTITY, "v1", 2)), true, NOW);

        assertThat(action.kind()).isEqualTo(ReconciliationKind.STALE_EDGE);
        assertThat(action.resolutionDecision()).isNull();
        assertThat(action.activeState()).isNotNull();
    }

    @Test
    void completedNonResolvedWithoutActiveEdgeIsConsistent() {
        ReconciliationAction action = policy.decide(
                work("COMPLETED", 3, NOW.plusSeconds(300)),
                result("AMBIGUOUS", null), Optional.empty(), false, NOW);

        assertThat(action.kind()).isEqualTo(ReconciliationKind.NOOP);
    }

    @Test
    void processingWithHealthyLeaseIsNeverStolen() {
        ReconciliationAction action = policy.decide(
                work("PROCESSING", 3, NOW.plusSeconds(60)),
                null, Optional.of(state(ENTITY, "v1", 3)), true, NOW);

        assertThat(action.kind()).isEqualTo(ReconciliationKind.NOOP);
    }

    @Test
    void processingWithExpiredLeaseAndExactGraphCompletesCassandra() {
        ReconciliationAction action = policy.decide(
                work("PROCESSING", 3, NOW.minusSeconds(1)),
                null, Optional.of(state(ENTITY, "v1", 3)), true, NOW);

        assertThat(action.kind()).isEqualTo(ReconciliationKind.REPAIR_CASSANDRA);
        assertThat(action.resolutionId()).isEqualTo(resolutionId(ENTITY, "v1"));
        assertThat(action.resolutionDecision().fencingToken()).isEqualTo(3);
    }

    @Test
    void processingWithExpiredLeaseButCorruptGraphIdentityIsContradiction() {
        GraphResolutionObservation tampered = new GraphResolutionObservation(
                VERSION.toString(), "not-the-deterministic-id", ENTITY, 3,
                "v1", "active", 0.5, 0.1, "RRF", false);
        ReconciliationAction action = policy.decide(
                work("PROCESSING", 3, NOW.minusSeconds(1)), null,
                Optional.of(tampered), true, NOW);

        assertThat(action.kind()).isEqualTo(ReconciliationKind.CONTRADICTION);
    }

    @Test
    void processingWithExpiredLeaseAndNoGraphIsLeftToNormalRetry() {
        ReconciliationAction action = policy.decide(
                work("PROCESSING", 3, NOW.minusSeconds(1)), null,
                Optional.empty(), false, NOW);

        assertThat(action.kind()).isEqualTo(ReconciliationKind.NOOP);
    }

    @Test
    void multipleActiveResolutionsAreContradictionNotSilentlyOneEdge() {
        GraphResolutionObservation multiple = new GraphResolutionObservation(
                VERSION.toString(), resolutionId(ENTITY, "v1"), ENTITY, 3,
                "v1", "active", 0.5, 0.1, "RRF", true);
        ReconciliationAction action = policy.decide(
                work("COMPLETED", 3, NOW.plusSeconds(300)),
                result("RESOLVED", ENTITY), Optional.of(multiple), true, NOW);

        assertThat(action.kind()).isEqualTo(ReconciliationKind.CONTRADICTION);
        assertThat(action.resolutionDecision()).isNull();
    }

    @Test
    void completedWithoutPersistedDecisionIsContradiction() {
        ReconciliationAction action = policy.decide(
                work("COMPLETED", 3, NOW.plusSeconds(300)), null,
                Optional.empty(), false, NOW);

        assertThat(action.kind()).isEqualTo(ReconciliationKind.CONTRADICTION);
    }

    private static ResolutionWorkRow work(String status, int attempt, Instant lease) {
        return new ResolutionWorkRow(TENANT, VERSION, MENTION, "connector", "source", "resource",
                UUID.fromString("22222222-2222-2222-2222-222222222222"), 0, 0, 3, 0, 3,
                "Acme", "Organization", status, "owner", lease, attempt, null, NOW, NOW,
                null, null, null);
    }

    private static ResolutionResultRow result(String outcome, String entity) {
        return new ResolutionResultRow(TENANT, VERSION, MENTION,
                UUID.fromString("22222222-2222-2222-2222-222222222222"), 0, 0, 3, 0, 3,
                "Acme", "Organization", outcome, "TEST", entity, entity == null ? null : "Acme",
                0.5, 0.1, "v1", "active", "{}", NOW, null);
    }

    private static GraphResolutionObservation state(String entity, String policyVersion, long fence) {
        return new GraphResolutionObservation(
                VERSION.toString(), resolutionId(entity, policyVersion), entity, fence,
                policyVersion, "active", 0.5, 0.1, "RRF", false);
    }

    private static String resolutionId(String entity, String policyVersion) {
        return ResolutionIds.of(new ResolutionDecision(TENANT, VERSION.toString(), MENTION,
                ResolutionOutcome.RESOLVED, "TEST", entity, "Acme", 0.5, 0.1,
                policyVersion, "active", Map.of(), "RRF"));
    }
}
