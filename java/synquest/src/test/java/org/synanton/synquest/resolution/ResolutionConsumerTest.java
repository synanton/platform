package org.synanton.synquest.resolution;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.synanton.common.resolution.FailureClass;
import org.synanton.common.resolution.ResolutionCandidateProvider;
import org.synanton.common.resolution.ResolutionDecision;
import org.synanton.common.resolution.ResolutionContext;
import org.synanton.common.resolution.ResolutionOutcome;
import org.synanton.common.resolution.ResolutionPolicy;
import org.synanton.common.resolution.RetryableProcessingException;
import org.synanton.common.resolution.StaleResolutionException;
import org.synanton.ingestioncache.claim.ClaimService;
import org.synanton.ingestioncache.domain.ResolutionWorkRow;
import org.synanton.synquest.config.ResolutionConsumerProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResolutionConsumerTest {

    private final ClaimService claims = mock(ClaimService.class);
    private final ResolutionCandidateProvider provider = mock(ResolutionCandidateProvider.class);
    private final ResolutionPolicy policy = mock(ResolutionPolicy.class);
    private final ResolutionWriteClient writer = mock(ResolutionWriteClient.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ResolutionConsumerProperties properties =
            new ResolutionConsumerProperties(true, "tenant-a", 10, 5, 30, 300, 5000L, "active");
    private final ResolutionConsumer consumer =
            new ResolutionConsumer(claims, provider, policy, writer, properties, registry);

    @Test
    void unresolvedOutcomeIsTerminalSuccessAndNeverWritesGraph() {
        ResolutionWorkRow work = work();
        when(claims.pendingResolutions(eq("tenant-a"), anyInt(), any())).thenReturn(List.of(work));
        when(claims.claimResolution(eq(work), anyString(), any(), any())).thenReturn(true);
        when(provider.candidates(any())).thenReturn(List.of());
        when(policy.decide(any())).thenReturn(decision(ResolutionOutcome.UNRESOLVED));
        when(claims.completeResolution(eq(work), anyString(), any(), any())).thenReturn(true);

        consumer.poll();

        verify(writer, never()).upsertResolution(any());
        verify(claims).completeResolution(eq(work), anyString(), any(), any());
        assertThat(registry.get("resolution.completed").counter().count()).isEqualTo(1);
        assertThat(registry.get("resolution.failed").counter().count()).isZero();
        assertThat(registry.get("resolution.latency").timer().count()).isEqualTo(1);
        assertThat(registry.get("resolution.claim_age").summary().count()).isEqualTo(1);
    }

    @Test
    void resolvedOutcomeWritesFencedDecisionBeforeCompletion() {
        ResolutionWorkRow work = work();
        when(claims.pendingResolutions(eq("tenant-a"), anyInt(), any())).thenReturn(List.of(work));
        when(claims.claimResolution(eq(work), anyString(), any(), any())).thenReturn(true);
        when(provider.candidates(any())).thenReturn(List.of());
        when(policy.decide(any())).thenReturn(decision(ResolutionOutcome.RESOLVED));
        when(claims.completeResolution(eq(work), anyString(), any(), any())).thenReturn(true);

        consumer.poll();

        verify(writer).upsertResolution(any());
        verify(claims).completeResolution(eq(work), anyString(), any(), any());
    }

    @Test
    void retryableGraphFailureReleasesWithoutTerminalCompletion() {
        ResolutionWorkRow work = work();
        when(claims.pendingResolutions(eq("tenant-a"), anyInt(), any())).thenReturn(List.of(work));
        when(claims.claimResolution(eq(work), anyString(), any(), any())).thenReturn(true);
        when(provider.candidates(any())).thenReturn(List.of());
        when(policy.decide(any())).thenReturn(decision(ResolutionOutcome.RESOLVED));
        org.mockito.Mockito.doThrow(new RetryableProcessingException("neo4j down"))
                .when(writer).upsertResolution(any());

        consumer.poll();

        verify(claims).releaseResolution(eq(work), anyString(),
                eq(FailureClass.INFRASTRUCTURE_RETRYABLE), anyString(), eq(5), eq(30L), any());
        verify(claims, never()).completeResolution(any(), anyString(), any(), any());
        assertThat(registry.get("resolution.failed").counter().count()).isEqualTo(1);
    }

    @Test
    void staleFenceIsRejectedTerminalWorkNotFailure() {
        ResolutionWorkRow work = work();
        when(claims.pendingResolutions(eq("tenant-a"), anyInt(), any())).thenReturn(List.of(work));
        when(claims.claimResolution(eq(work), anyString(), any(), any())).thenReturn(true);
        when(provider.candidates(any())).thenReturn(List.of());
        when(policy.decide(any())).thenReturn(decision(ResolutionOutcome.RESOLVED));
        org.mockito.Mockito.doThrow(new StaleResolutionException("newer fence"))
                .when(writer).upsertResolution(any());

        consumer.poll();

        verify(claims).supersedeResolution(eq(work), anyString(), anyString(), any());
        verify(claims, never()).completeResolution(any(), anyString(), any(), any());
        verify(claims, never()).releaseResolution(any(), anyString(), any(), anyString(),
                anyInt(), any(Long.class), any());
        assertThat(registry.get("resolution.stale").counter().count()).isEqualTo(1);
        assertThat(registry.get("resolution.failed").counter().count()).isZero();
    }

    @Test
    void lostResponseAfterCommitReplaysSameWorkAtHigherFence() {
        UUID version = UUID.randomUUID();
        ResolutionWorkRow first = work(version, "mention-1", 0);
        ResolutionWorkRow retry = work(version, "mention-1", 1);
        when(claims.pendingResolutions(eq("tenant-a"), anyInt(), any()))
                .thenReturn(List.of(first))
                .thenReturn(List.of(retry));
        when(claims.claimResolution(any(), anyString(), any(), any())).thenReturn(true);
        when(provider.candidates(any())).thenReturn(List.of());
        when(policy.decide(any())).thenAnswer(invocation -> {
            ResolutionContext context = invocation.getArgument(0);
            return new ResolutionDecision(context.tenantId(), context.sourceVersionId(),
                    context.mentionId(), ResolutionOutcome.RESOLVED, "TEST", "entity-1",
                    "Entity", 0.5, 0.1, "v1", "active", Map.of(), "RRF");
        });
        when(claims.completeResolution(any(), anyString(), any(), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new RetryableProcessingException("response lost after commit"))
                .doNothing().when(writer).upsertResolution(any());

        consumer.poll();
        consumer.poll();

        ArgumentCaptor<ResolutionDecision> decisions = ArgumentCaptor.forClass(ResolutionDecision.class);
        verify(writer, times(2)).upsertResolution(decisions.capture());
        assertThat(decisions.getAllValues())
                .extracting(ResolutionDecision::sourceVersionId)
                .containsOnly(version.toString());
        assertThat(decisions.getAllValues())
                .extracting(ResolutionDecision::mentionId)
                .containsOnly("mention-1");
        assertThat(decisions.getAllValues())
                .extracting(ResolutionDecision::fencingToken)
                .containsExactly(1L, 2L);
        verify(claims).releaseResolution(eq(first), anyString(),
                eq(FailureClass.INFRASTRUCTURE_RETRYABLE), anyString(), eq(5), eq(30L), any());
        verify(claims).completeResolution(eq(retry), anyString(), any(), any());
        assertThat(registry.get("resolution.completed").counter().count()).isEqualTo(1);
    }

    @Test
    void disabledConsumerPollsNothing() {
        ResolutionConsumerProperties disabled =
                new ResolutionConsumerProperties(false, "tenant-a", 10, 5, 30, 300, 5000L, "active");
        ResolutionConsumer off = new ResolutionConsumer(claims, provider, policy, writer, disabled,
                new SimpleMeterRegistry());

        off.poll();

        verify(claims, never()).pendingResolutions(anyString(), anyInt(), any());
    }

    @Test
    void claimLossDoesNotCompleteOrWrite() {
        ResolutionWorkRow work = work();
        when(claims.pendingResolutions(eq("tenant-a"), anyInt(), any())).thenReturn(List.of(work));
        when(claims.claimResolution(eq(work), anyString(), any(), any())).thenReturn(false);

        consumer.poll();

        verify(writer, never()).upsertResolution(any());
        verify(claims, never()).completeResolution(any(), anyString(), any(), any());
        assertThat(registry.get("resolution.claimed").counter().count()).isZero();
    }

    private static ResolutionWorkRow work() {
        return work(0);
    }

    private static ResolutionWorkRow work(int attempts) {
        return work(UUID.randomUUID(), "mention-1", attempts);
    }

    private static ResolutionWorkRow work(UUID version, String mentionId, int attempts) {
        return new ResolutionWorkRow(
                "tenant-a", version, mentionId, "connector", "source", "resource",
                UUID.randomUUID(), 0, 0, 3, 0, 3, "Acme", "Organization",
                "PENDING", null, null, attempts, null, null, Instant.now(), null, null, null);
    }

    private static ResolutionDecision decision(ResolutionOutcome outcome) {
        return new ResolutionDecision("tenant-a", UUID.randomUUID().toString(), "mention-1",
                outcome, "TEST", outcome == ResolutionOutcome.RESOLVED ? "entity-1" : null,
                null, 0.5, 0.1, "v1", "active", Map.of(), "RRF");
    }
}
