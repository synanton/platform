package org.synanton.relix.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.synanton.common.resolution.CanonicalRelation;
import org.synanton.common.resolution.GraphIdentity;
import org.synanton.common.resolution.FenceBehindException;
import org.synanton.common.resolution.PromotionResult;
import org.synanton.common.resolution.SourceRevisionIdentity;
import org.synanton.common.resolution.StaleGenerationException;
import org.synanton.common.resolution.UnauthorizedGenerationException;
import org.synanton.relix.identity.GraphIdentityResolver;
import org.synanton.relix.identity.SourceGenerationAuthority;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.RelationPromotionResultRow;

class RelixCanonicalRelationPromoterTest {

    private static final String TENANT = "tenant-promoter";
    private static final UUID VERSION = UUID.randomUUID();
    private final IngestionCacheClient cache = mock(IngestionCacheClient.class);
    private final GraphWritePort writePort = mock(GraphWritePort.class);
    private final GraphIdentityResolver resolver = mock(GraphIdentityResolver.class);
    private final SourceGenerationAuthority authority = mock(SourceGenerationAuthority.class);
    private final GraphIdentity identity = GraphIdentity.canonical(TENANT, "v1");
    private final RelixCanonicalRelationPromoter promoter =
            new RelixCanonicalRelationPromoter(cache, writePort, resolver, authority);

    @Test
    void validRowDelegatesAndInvalidRowFailsClosed() {
        when(cache.listRelationPromotionResults(TENANT, VERSION)).thenReturn(List.of(
                row("VALID"), row("INVALID")));
        when(resolver.resolveCanonical(TENANT)).thenReturn(identity);
        when(authority.acceptedGeneration(any())).thenReturn(java.util.Optional.of(1L));
        when(writePort.promoteRelation(any(), any(), any())).thenReturn(new PromotionResult(1, 1, 1));

        assertThat(promoter.promoteRelation(relation())).isEqualTo(new PromotionResult(1, 1, 1));
        verify(writePort).promoteRelation(eq(identity), eq(relation()), eq(
                new SourceRevisionIdentity(TENANT, "source-1", 1)));
    }

    @Test
    void rejectedAuthorityGenerationIsTerminalAndNeverCallsTheGraph() {
        when(cache.listRelationPromotionResults(TENANT, VERSION)).thenReturn(List.of(row("VALID")));
        when(authority.acceptedGeneration(any())).thenReturn(java.util.Optional.of(2L));

        assertThatThrownBy(() -> promoter.promoteRelation(relation()))
                .isInstanceOf(UnauthorizedGenerationException.class);
        verify(writePort, never()).promoteRelation(any(), any(), any());
    }

    @Test
    void fenceBehindRemainsRetryable() {
        when(cache.listRelationPromotionResults(TENANT, VERSION)).thenReturn(List.of(row("VALID")));
        when(authority.acceptedGeneration(any())).thenReturn(java.util.Optional.of(1L));
        when(resolver.resolveCanonical(TENANT)).thenReturn(identity);
        when(writePort.promoteRelation(any(), any(), any()))
                .thenThrow(new FenceBehindException("reconciliation pending"));

        assertThatThrownBy(() -> promoter.promoteRelation(relation()))
                .isInstanceOf(FenceBehindException.class);
    }

    @Test
    void staleGenerationIsTerminal() {
        when(cache.listRelationPromotionResults(TENANT, VERSION)).thenReturn(List.of(row("VALID")));
        when(authority.acceptedGeneration(any())).thenReturn(java.util.Optional.of(1L));
        when(resolver.resolveCanonical(TENANT)).thenReturn(identity);
        when(writePort.promoteRelation(any(), any(), any()))
                .thenThrow(new StaleGenerationException("newer fence"));

        assertThatThrownBy(() -> promoter.promoteRelation(relation()))
                .isInstanceOf(StaleGenerationException.class);
    }

    @Test
    void concurrentTenantsPromoteOnlyTheirOwnRelations() throws Exception {
        String tenantB = "tenant-promoter-b";
        UUID versionB = UUID.randomUUID();
        GraphIdentity identityB = GraphIdentity.canonical(tenantB, "v1");
        CanonicalRelation relationA = relationFor(TENANT, VERSION, "r-a");
        CanonicalRelation relationB = relationFor(tenantB, versionB, "r-b");
        when(cache.listRelationPromotionResults(anyString(), any(UUID.class)))
                .thenAnswer(invocation -> List.of(rowFor(
                        invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(0, String.class).equals(TENANT) ? "r-a" : "r-b")));
        when(resolver.resolveCanonical(TENANT)).thenReturn(identity);
        when(resolver.resolveCanonical(tenantB)).thenReturn(identityB);
        when(authority.acceptedGeneration(any())).thenReturn(java.util.Optional.of(1L));
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(writePort.promoteRelation(any(), any(), any())).thenAnswer(invocation -> {
            assertThat(((GraphIdentity) invocation.getArgument(0)).tenantId())
                    .isIn(TENANT, tenantB);
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("second tenant did not enter promotion");
            }
            return new PromotionResult(1, 1, 1);
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<PromotionResult> a = pool.submit(() -> promoter.promoteRelation(relationA));
        Future<PromotionResult> b = pool.submit(() -> promoter.promoteRelation(relationB));
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
        assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(new PromotionResult(1, 1, 1));
        assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo(new PromotionResult(1, 1, 1));
        pool.shutdownNow();

        verify(writePort).promoteRelation(eq(identity), eq(relationA), any());
        verify(writePort).promoteRelation(eq(identityB), eq(relationB), any());
    }

    @Test
    void invalidValidationRowRecordsContradictionAndNeverPromotes() {
        when(cache.listRelationPromotionResults(TENANT, VERSION))
                .thenReturn(List.of(row("INVALID", "ONTOLOGY_INVALID:domain_mismatch")));
        when(resolver.resolveCanonical(TENANT)).thenReturn(identity);
        when(authority.acceptedGeneration(any())).thenReturn(java.util.Optional.of(1L));
        when(writePort.recordContradiction(any(), any(), any()))
                .thenReturn(new PromotionResult(0, 1, 0));

        assertThat(promoter.promoteRelation(relation())).isEqualTo(new PromotionResult(0, 1, 0));
        verify(writePort).recordContradiction(eq(identity), any(), eq(
                new SourceRevisionIdentity(TENANT, "source-1", 1)));
        verify(writePort, never()).promoteRelation(any(), any(), any());
    }

    @Test
    void classifiesDomainMismatchAsContradiction() {
        assertThat(RelixCanonicalRelationPromoter.isContradiction(
                "ONTOLOGY_INVALID:domain_mismatch")).isTrue();
        assertThat(RelixCanonicalRelationPromoter.isContradiction(
                "ONTOLOGY_INVALID:range_mismatch,domain_mismatch")).isTrue();
    }

    @Test
    void classifiesFailedAssertionsWithoutContradictionEvidence() {
        assertThat(RelixCanonicalRelationPromoter.isContradiction(
                "EXTRACTED_EVIDENCE_NOT_FOUND:EXTRACTED_EVIDENCE_NOT_FOUND")).isFalse();
        assertThat(RelixCanonicalRelationPromoter.isContradiction(
                "MISSING_ACTIVE_RESOLUTION:MISSING_ACTIVE_RESOLUTION")).isFalse();
        assertThat(RelixCanonicalRelationPromoter.isContradiction(
                "ONTOLOGY_INVALID:unknown_predicate")).isFalse();
        assertThat(RelixCanonicalRelationPromoter.isContradiction(
                "ONTOLOGY_INVALID:unknown_subject_type,unknown_predicate")).isFalse();
        assertThat(RelixCanonicalRelationPromoter.isContradiction(
                "ONTOLOGY_INVALID:domain_mismatch,unknown_object_type")).isFalse();
        assertThat(RelixCanonicalRelationPromoter.isContradiction(
                "ONTOLOGY_INVALID")).isFalse();
    }

    @Test
    void missingValidationRowNeverCallsTheGraph() {
        when(cache.listRelationPromotionResults(TENANT, VERSION)).thenReturn(List.of());
        assertThatThrownBy(() -> promoter.promoteRelation(relation()))
                .isInstanceOf(IllegalStateException.class);
        verify(writePort, never()).promoteRelation(any(), any(), any());
    }

    private static CanonicalRelation relation() {
        return relationFor(TENANT, VERSION, "r1");
    }

    private static CanonicalRelation relationFor(String tenant, UUID version, String relationId) {
        return new CanonicalRelation(tenant, version.toString(), relationId, "m1", "e1",
                "m2", "e2", "requires", "1.0.0", "ref:0", 0, 20,
                "source-1", version.toString(), 1, null);
    }

    private static RelationPromotionResultRow row(String status) {
        return row(status, status);
    }

    private static RelationPromotionResultRow row(String status, String reason) {
        return new RelationPromotionResultRow(TENANT, VERSION, "r1", "m1", "m2", "requires",
                status, "e1", "e2", reason, "{}", "relation-validation-v1", "1.0.0",
                Instant.now(), null, "source-1", VERSION, 1, null, "CURRENT");
    }

    private static RelationPromotionResultRow rowFor(String tenant, UUID version, String relationId) {
        return new RelationPromotionResultRow(tenant, version, relationId, "m1", "m2", "requires",
                "VALID", "e1", "e2", "VALID", "{}", "relation-validation-v1", "1.0.0",
                Instant.now(), null, "source-1", version, 1, null, "CURRENT");
    }
}
