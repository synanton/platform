package org.synanton.synquest.resolution;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.common.resolution.ContractViolationException;
import org.synanton.common.resolution.FailureClass;
import org.synanton.common.resolution.FailureClassifier;
import org.synanton.common.resolution.ResolutionCandidate;
import org.synanton.common.resolution.ResolutionCandidateProvider;
import org.synanton.common.resolution.ResolutionContext;
import org.synanton.common.resolution.ResolutionDecision;
import org.synanton.common.resolution.ResolutionOutcome;
import org.synanton.common.resolution.ResolutionPolicy;
import org.synanton.common.resolution.StaleResolutionException;
import org.synanton.ingestioncache.claim.ClaimService;
import org.synanton.ingestioncache.domain.ResolutionWorkRow;
import org.synanton.synquest.config.ResolutionConsumerProperties;

/**
 * PR #4d.1 resolution orchestration. It claims durable work, retrieves
 * candidates, evaluates the pure policy, writes only RESOLVED decisions to
 * Relix, and completes/releases the claim. Extraction is never re-run.
 *
 * <p>A lost graph response is safe: the lease expires, the work is reclaimed,
 * the same deterministic decision is attempted again at a higher fence.
 * Ambiguous/UNRESOLVED outcomes are successful terminal runs, not failures.
 */
@Component
public final class ResolutionConsumer {

    private static final Logger log = LoggerFactory.getLogger(ResolutionConsumer.class);

    private final ClaimService claims;
    private final ResolutionCandidateProvider candidateProvider;
    private final ResolutionPolicy policy;
    private final ResolutionWriteClient graphWriter;
    private final ResolutionConsumerProperties properties;
    private final MeterRegistry registry;
    private final String workerId = UUID.randomUUID().toString();
    private final Clock clock = Clock.systemUTC();

    private final Counter claimed;
    private final Counter completed;
    private final Counter failed;
    private final Counter stale;
    private final Timer latency;
    private final DistributionSummary claimAge;

    public ResolutionConsumer(ClaimService claims, ResolutionCandidateProvider candidateProvider,
                              ResolutionPolicy policy, ResolutionWriteClient graphWriter,
                              ResolutionConsumerProperties properties, MeterRegistry registry) {
        this.claims = claims;
        this.candidateProvider = candidateProvider;
        this.policy = policy;
        this.graphWriter = graphWriter;
        this.properties = properties;
        this.registry = registry;
        this.claimed = registry.counter("resolution.claimed");
        this.completed = registry.counter("resolution.completed");
        this.failed = registry.counter("resolution.failed");
        this.stale = registry.counter("resolution.stale");
        this.latency = Timer.builder("resolution.latency").register(registry);
        this.claimAge = DistributionSummary.builder("resolution.claim_age")
                .baseUnit("milliseconds").register(registry);
    }

    @Scheduled(fixedDelayString = "${synquest.resolution.consumer.poll-delay-ms:5000}")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        String tenant = properties.tenant();
        if (tenant == null || tenant.isBlank()) {
            log.warn("Resolution consumer is enabled without a tenant; skipping poll");
            return;
        }
        Instant now = clock.instant();
        try {
            claims.reclaimExpiredResolutions(tenant, now, properties.batchMentionsOrDefault());
        } catch (Exception e) {
            log.warn("Resolution lease reclaim failed tenant={}: {}", tenant, e.toString());
        }
        List<ResolutionWorkRow> pending;
        try {
            pending = claims.pendingResolutions(tenant, properties.batchMentionsOrDefault(), now);
        } catch (Exception e) {
            log.warn("Resolution poll failed tenant={}: {}", tenant, e.toString());
            return;
        }
        for (ResolutionWorkRow row : pending) {
            process(row);
        }
    }

    private void process(ResolutionWorkRow row) {
        Instant now = clock.instant();
        try {
            if (!claims.claimResolution(row, workerId, now,
                    Duration.ofSeconds(properties.leaseSecondsOrDefault()))) {
                return; // another consumer owns it
            }
            claimed.increment();
            if (row.workCreatedAt() != null) {
                claimAge.record(Math.max(0, Duration.between(row.workCreatedAt(), now).toMillis()));
            }
        } catch (Exception e) {
            log.warn("Resolution claim failed mention={}: {}", row.mentionId(), e.toString());
            return;
        }

        Instant claimedAt = now;
        try {
            ResolutionContext base = new ResolutionContext(
                    row.tenantId(), row.sourceVersionId().toString(), row.mentionId(),
                    row.surfaceForm(), row.proposedType(), List.of(),
                    properties.ontologyVersionOrDefault());
            List<ResolutionCandidate> candidates = candidateProvider.candidates(base);
            ResolutionContext context = new ResolutionContext(
                    row.tenantId(), row.sourceVersionId().toString(), row.mentionId(),
                    row.surfaceForm(), row.proposedType(), candidates,
                    properties.ontologyVersionOrDefault());
            ResolutionDecision decision = policy.decide(context)
                    .withFencingToken(row.attemptCount() + 1L);

            if (decision.outcome() == ResolutionOutcome.RESOLVED) {
                graphWriter.upsertResolution(decision);
            }
            if (!claims.completeResolution(row, workerId, decision, clock.instant())) {
                log.warn("Resolution completion lost ownership mention={}", row.mentionId());
                return;
            }
            completed.increment();
            registry.counter("resolution.outcome", "outcome", decision.outcome().name()).increment();
            refresh(row);
        } catch (StaleResolutionException e) {
            stale.increment();
            claims.supersedeResolution(row, workerId, e.getMessage(), clock.instant());
            refresh(row);
            log.info("Stale resolution rejected mention={} attempt={}: {}",
                    row.mentionId(), row.attemptCount() + 1, e.getMessage());
        } catch (ContractViolationException e) {
            failed.increment();
            claims.releaseResolution(row, workerId, FailureClass.CONTRACT_VIOLATION,
                    e.getMessage(), properties.maxAttemptsOrDefault(),
                    properties.retryBackoffSecondsOrDefault(), clock.instant());
            refresh(row);
            log.warn("Resolution contract failure mention={}: {}", row.mentionId(), e.toString());
        } catch (Exception e) {
            FailureClass failureClass = FailureClassifier.classify(e);
            failed.increment();
            if (failureClass == FailureClass.INFRASTRUCTURE_RETRYABLE
                    && row.attemptCount() + 1 < properties.maxAttemptsOrDefault()) {
                registry.counter("resolution.retried").increment();
            }
            claims.releaseResolution(row, workerId, failureClass, e.toString(),
                    properties.maxAttemptsOrDefault(),
                    properties.retryBackoffSecondsOrDefault(), clock.instant());
            refresh(row);
            log.warn("Resolution failure mention={} class={}: {}",
                    row.mentionId(), failureClass, e.toString());
        } finally {
            latency.record(Duration.between(claimedAt, clock.instant()));
        }
    }

    private void refresh(ResolutionWorkRow row) {
        try {
            claims.refreshVersionStatus(row, clock.instant());
        } catch (Exception e) {
            log.warn("Resolution status refresh failed version={}: {}",
                    row.sourceVersionId(), e.toString());
        }
    }
}
