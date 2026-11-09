package org.synanton.relix.consumer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synanton.common.resolution.CanonicalRelation;
import org.synanton.common.resolution.FailureClass;
import org.synanton.common.resolution.PromotionResult;
import org.synanton.common.resolution.RetryableProcessingException;
import org.synanton.ingestioncache.claim.ClaimService;
import org.synanton.ingestioncache.domain.RelationPromotionResultRow;
import org.synanton.ingestioncache.domain.RelationPromotionWorkRow;
import org.synanton.relix.config.PromotionConsumerProperties;
import org.synanton.relix.graph.CanonicalRelationPromotionPort;

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

class PromotionConsumerTest {

    private final ClaimService claims = mock(ClaimService.class);
    private final CanonicalRelationPromotionPort promoter = mock(CanonicalRelationPromotionPort.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PromotionConsumerProperties properties =
            new PromotionConsumerProperties(true, "tenant-a", 5, 5, 30, 120, 10000L);
    private final PromotionConsumer consumer =
            new PromotionConsumer(claims, promoter, properties, registry);

    @Test
    void validPromotionIsClaimedPromotedAndCompleted() {
        RelationPromotionWorkRow work = work();
        when(claims.pendingPromotions(eq("tenant-a"), anyInt(), any())).thenReturn(List.of(work));
        when(claims.claimPromotion(eq(work), anyString(), any(), any())).thenReturn(true);
        when(promoter.promoteRelation(any())).thenReturn(new PromotionResult(1, 1, 1));
        when(claims.completePromotion(eq(work), anyString(), any())).thenReturn(true);

        consumer.poll();

        verify(promoter).promoteRelation(any(CanonicalRelation.class));
        verify(claims).completePromotion(eq(work), anyString(), any());
        assertThat(registry.get("promotion.completed").counter().count()).isEqualTo(1);
        assertThat(registry.get("promotion.latency").timer().count()).isEqualTo(1);
        assertThat(registry.get("promotion.claim_age").summary().count()).isEqualTo(1);
    }

    @Test
    void retryablePromotionFailureReleasesWithoutCompletion() {
        RelationPromotionWorkRow work = work();
        when(claims.pendingPromotions(eq("tenant-a"), anyInt(), any())).thenReturn(List.of(work));
        when(claims.claimPromotion(eq(work), anyString(), any(), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new RetryableProcessingException("neo4j down"))
                .when(promoter).promoteRelation(any());

        consumer.poll();

        verify(claims).releasePromotion(eq(work), anyString(),
                eq(FailureClass.INFRASTRUCTURE_RETRYABLE), anyString(), eq(5), eq(30L), any());
        verify(claims, never()).completePromotion(any(), anyString(), any());
        assertThat(registry.get("promotion.failed").counter().count()).isEqualTo(1);
    }

    @Test
    void disabledPromotionConsumerPollsNothing() {
        PromotionConsumerProperties disabled =
                new PromotionConsumerProperties(false, "tenant-a", 5, 5, 30, 120, 10000L);
        PromotionConsumer off = new PromotionConsumer(claims, promoter, disabled,
                new SimpleMeterRegistry());

        off.poll();

        verify(claims, never()).pendingPromotions(anyString(), anyInt(), any());
    }

    @Test
    void invalidEvidenceIsContractFailureAndNotRetriedAsInfrastructure() {
        RelationPromotionWorkRow work = workWithoutEvidence();
        when(claims.pendingPromotions(eq("tenant-a"), anyInt(), any())).thenReturn(List.of(work));
        when(claims.claimPromotion(eq(work), anyString(), any(), any())).thenReturn(true);

        consumer.poll();

        verify(claims).releasePromotion(eq(work), anyString(),
                eq(FailureClass.CONTRACT_VIOLATION), anyString(), eq(5), eq(30L), any());
        verify(claims, never()).completePromotion(any(), anyString(), any());
        verify(promoter, never()).promoteRelation(any());
    }

    private static RelationPromotionWorkRow work() {
        return workWithEvidence("""
                {"view":{"relationId":"r1","tenantId":"tenant-a","sourceVersionId":"%s",
                "subjectMentionId":"m1","objectMentionId":"m2","predicate":"requires",
                "evidenceChunkId":"c:0","evidenceChunkOffsetStart":1,
                "evidenceChunkOffsetEnd":9}}
                """.formatted(UUID.randomUUID()));
    }

    private static RelationPromotionWorkRow workWithoutEvidence() {
        return workWithEvidence("{}");
    }

    private static RelationPromotionWorkRow workWithEvidence(String evidenceJson) {
        return new RelationPromotionWorkRow(
                new RelationPromotionResultRow("tenant-a", UUID.randomUUID(), "r1", "m1", "m2",
                        "requires", "VALID", "entity-1", "entity-2", "VALID", evidenceJson,
                        "relation-validation-v1", "active", Instant.now(), null),
                "PENDING", null, null, 0, null, null, null, null);
    }
}
