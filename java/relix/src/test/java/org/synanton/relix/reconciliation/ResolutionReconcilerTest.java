package org.synanton.relix.reconciliation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.synanton.common.resolution.ResolutionDecision;
import org.synanton.common.resolution.ResolutionGraphState;
import org.synanton.common.resolution.ResolutionOutcome;
import org.synanton.ingestioncache.claim.ClaimService;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ResolutionResultRow;
import org.synanton.ingestioncache.domain.ResolutionWorkRow;
import org.synanton.relix.config.ReconciliationProperties;
import org.synanton.relix.graph.GraphConnector;
import org.synanton.relix.graph.ResolutionIds;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ResolutionReconcilerTest {

    private static final String TENANT = "tenant-reconcile";
    private static final UUID VERSION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String MENTION = "mention-1";
    private static final String ENTITY = "entity-1";
    private static final Instant NOW = Instant.now();

    private final IngestionCacheClient cache = mock(IngestionCacheClient.class);
    private final ClaimService claims = mock(ClaimService.class);
    private final GraphConnector graph = mock(GraphConnector.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ReconciliationProperties properties =
            new ReconciliationProperties(true, TENANT, 100, 60000L, 120);
    private final ResolutionReconciler reconciler =
            new ResolutionReconciler(cache, claims, graph, properties, registry);

    @Test
    void staleActiveEdgeProducesMetricAndIdentifiersButNoMutation(CapturedOutput output) {
        ResolutionWorkRow work = work("COMPLETED", 3, NOW.plusSeconds(300));
        ResolutionResultRow result = result("UNRESOLVED");
        when(graph.supportsResolutionReconciliation()).thenReturn(true);
        when(claims.resolutionWorkForTenant(TENANT)).thenReturn(List.of(work));
        when(cache.listResolutionResults(TENANT, VERSION)).thenReturn(List.of(result));
        when(graph.getResolutionStates(TENANT, MENTION))
                .thenReturn(List.of(state(ENTITY, "v1", 2, true)));

        reconciler.poll();

        assertThat(registry.get("reconciliation.stale_edge").counter().count()).isEqualTo(1);
        assertThat(output.getOut()).contains(TENANT, MENTION, VERSION.toString(), ENTITY, "UNRESOLVED");
        verify(graph, never()).upsertResolution(any());
        verify(graph, never()).supersedeResolution(any(), any(), any(), any());
        verify(claims, never()).reclaimResolution(any(), any());
        verify(claims, never()).claimResolution(any(), anyString(), any(), any());
        verify(claims, never()).completeResolution(any(), anyString(), any(), any());
        verify(claims, never()).releaseResolution(any(), anyString(), any(), anyString(),
                anyInt(), anyLong(), any());
        verify(claims, never()).supersedeResolution(any(), anyString(), anyString(), any());
    }

    @Test
    void missingGraphForPersistedResolvedDecisionIsRepaired() {
        ResolutionWorkRow work = work("COMPLETED", 3, NOW.plusSeconds(300));
        ResolutionResultRow result = result("RESOLVED");
        when(graph.supportsResolutionReconciliation()).thenReturn(true);
        when(claims.resolutionWorkForTenant(TENANT)).thenReturn(List.of(work));
        when(cache.listResolutionResults(TENANT, VERSION)).thenReturn(List.of(result));
        when(graph.getResolutionStates(TENANT, MENTION)).thenReturn(List.of());

        reconciler.poll();

        verify(graph).upsertResolution(any(ResolutionDecision.class));
        assertThat(registry.get("reconciliation.graph_repaired").counter().count()).isEqualTo(1);
    }

    @Test
    void expiredLeaseWithExactGraphCompletesCassandra() {
        ResolutionWorkRow work = work("PROCESSING", 3, NOW.minusSeconds(1));
        when(graph.supportsResolutionReconciliation()).thenReturn(true);
        when(claims.resolutionWorkForTenant(TENANT)).thenReturn(List.of(work));
        when(cache.listResolutionResults(TENANT, VERSION)).thenReturn(List.of());
        when(graph.getResolutionStates(TENANT, MENTION))
                .thenReturn(List.of(state(ENTITY, "v1", 3, true)));
        when(claims.reclaimResolution(eq(work), any())).thenReturn(true);
        when(claims.claimResolution(eq(work), anyString(), any(), any())).thenReturn(true);
        when(claims.completeResolution(eq(work), anyString(), any(), any())).thenReturn(true);

        reconciler.poll();

        verify(claims).reclaimResolution(eq(work), any());
        verify(claims).completeResolution(eq(work), anyString(), any(), any());
        assertThat(registry.get("reconciliation.cassandra_repaired").counter().count()).isEqualTo(1);
    }

    @Test
    void lostClaimDuringRepairDoesNotCompleteOrWrite() {
        ResolutionWorkRow work = work("PROCESSING", 3, NOW.minusSeconds(1));
        when(graph.supportsResolutionReconciliation()).thenReturn(true);
        when(claims.resolutionWorkForTenant(TENANT)).thenReturn(List.of(work));
        when(cache.listResolutionResults(TENANT, VERSION)).thenReturn(List.of());
        when(graph.getResolutionStates(TENANT, MENTION))
                .thenReturn(List.of(state(ENTITY, "v1", 3, true)));
        when(claims.reclaimResolution(eq(work), any())).thenReturn(true);
        when(claims.claimResolution(eq(work), anyString(), any(), any())).thenReturn(false);

        reconciler.poll();

        verify(claims, never()).completeResolution(any(), anyString(), any(), any());
        verify(graph, never()).upsertResolution(any());
        assertThat(registry.get("reconciliation.skipped").counter().count()).isEqualTo(1);
    }

    @Test
    void healthyLeaseIsNotStolenEvenWithMatchingGraph() {
        ResolutionWorkRow work = work("PROCESSING", 3, NOW.plusSeconds(60));
        when(graph.supportsResolutionReconciliation()).thenReturn(true);
        when(claims.resolutionWorkForTenant(TENANT)).thenReturn(List.of(work));
        when(cache.listResolutionResults(TENANT, VERSION)).thenReturn(List.of());
        when(graph.getResolutionStates(TENANT, MENTION))
                .thenReturn(List.of(state(ENTITY, "v1", 3, true)));

        reconciler.poll();

        verify(claims, never()).reclaimResolution(any(), any());
        verify(claims, never()).claimResolution(any(), anyString(), any(), any());
        verify(claims, never()).completeResolution(any(), anyString(), any(), any());
        verify(claims, never()).releaseResolution(any(), anyString(), any(), anyString(),
                anyInt(), anyLong(), any());
        verify(claims, never()).supersedeResolution(any(), anyString(), anyString(), any());
        verify(graph, never()).upsertResolution(any());
        verify(graph, never()).supersedeResolution(any(), any(), any(), any());
        assertThat(registry.get("reconciliation.lease_held").counter().count()).isEqualTo(1);
    }

    @Test
    void higherGraphFenceIsNotOverwrittenByOlderCassandraSnapshot() {
        ResolutionWorkRow work = work("COMPLETED", 2, NOW.plusSeconds(300));
        ResolutionResultRow result = result("RESOLVED");
        when(graph.supportsResolutionReconciliation()).thenReturn(true);
        when(claims.resolutionWorkForTenant(TENANT)).thenReturn(List.of(work));
        when(cache.listResolutionResults(TENANT, VERSION)).thenReturn(List.of(result));
        when(graph.getResolutionStates(TENANT, MENTION))
                .thenReturn(List.of(state(ENTITY, "v1", 3, true)));

        reconciler.poll();

        verify(graph, never()).upsertResolution(any());
        assertThat(registry.get("reconciliation.contradiction").counter().count()).isEqualTo(1);
    }

    private static ResolutionWorkRow work(String status, int attempt, Instant lease) {
        return new ResolutionWorkRow(TENANT, VERSION, MENTION, "connector", "source", "resource",
                UUID.fromString("22222222-2222-2222-2222-222222222222"), 0, 0, 3, 0, 3,
                "Acme", "Organization", status, "owner", lease, attempt, null, NOW, NOW,
                null, null, null);
    }

    private static ResolutionResultRow result(String outcome) {
        String entity = "RESOLVED".equals(outcome) ? ENTITY : null;
        return new ResolutionResultRow(TENANT, VERSION, MENTION,
                UUID.fromString("22222222-2222-2222-2222-222222222222"), 0, 0, 3, 0, 3,
                "Acme", "Organization", outcome, "TEST", entity, entity == null ? null : "Acme",
                0.5, 0.1, "v1", "active", "{}", NOW, null);
    }

    private static ResolutionGraphState state(String entity, String policyVersion, long fence,
                                              boolean active) {
        return new ResolutionGraphState(TENANT, MENTION, VERSION.toString(),
                resolutionId(), entity, fence, active, active ? "ACTIVE" : "SUPERSEDED",
                policyVersion, "active", 0.5, 0.1, "RRF", NOW.toString());
    }

    private static String resolutionId() {
        return ResolutionIds.of(new ResolutionDecision(TENANT, VERSION.toString(), MENTION,
                ResolutionOutcome.RESOLVED, "TEST", ENTITY, "Acme", 0.5, 0.1,
                "v1", "active", Map.of(), "RRF"));
    }
}
