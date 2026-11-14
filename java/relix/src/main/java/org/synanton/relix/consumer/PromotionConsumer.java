package org.synanton.relix.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.common.resolution.CanonicalRelation;
import org.synanton.common.resolution.ContractViolationException;
import org.synanton.common.resolution.FailureClass;
import org.synanton.common.resolution.FailureClassifier;
import org.synanton.common.resolution.FenceBehindException;
import org.synanton.common.resolution.RetryReason;
import org.synanton.common.resolution.RetryableProcessingException;
import org.synanton.common.resolution.StaleGenerationException;
import org.synanton.common.resolution.UnauthorizedGenerationException;
import org.synanton.relix.config.PromotionConsumerProperties;
import org.synanton.relix.graph.CanonicalRelationPromotionPort;
import org.synanton.ingestioncache.claim.ClaimService;
import org.synanton.ingestioncache.domain.RelationPromotionResultRow;
import org.synanton.ingestioncache.domain.RelationPromotionWorkRow;

/**
 * PR #4d.1 promotion orchestration. It claims only VALID validation rows and
 * delegates atomic graph invariants to the existing Relix promotion port. It
 * never re-implements validation and never re-runs extraction.
 */
@Component
public final class PromotionConsumer {

    private static final Logger log = LoggerFactory.getLogger(PromotionConsumer.class);

    private final ClaimService claims;
    private final CanonicalRelationPromotionPort promotionPort;
    private final PromotionConsumerProperties properties;
    private final MeterRegistry registry;
    private final CanonicalGraphCompletion completion;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String workerId = UUID.randomUUID().toString();
    private final Clock clock = Clock.systemUTC();

    private final Counter claimed;
    private final Counter completed;
    private final Counter failed;
    private final Counter exhausted;
    private final Timer latency;
    private final DistributionSummary claimAge;

    public PromotionConsumer(ClaimService claims, CanonicalRelationPromotionPort promotionPort,
                             PromotionConsumerProperties properties, MeterRegistry registry) {
        this(claims, promotionPort, properties, registry, null);
    }

    @Autowired
    public PromotionConsumer(ClaimService claims, CanonicalRelationPromotionPort promotionPort,
                             PromotionConsumerProperties properties, MeterRegistry registry,
                             CanonicalGraphCompletion completion) {
        this.claims = claims;
        this.promotionPort = promotionPort;
        this.properties = properties;
        this.registry = registry;
        this.completion = completion;
        this.claimed = registry.counter("promotion.claimed");
        this.completed = registry.counter("promotion.completed");
        this.failed = registry.counter("promotion.failed");
        this.exhausted = registry.counter("promotion.exhausted");
        this.latency = Timer.builder("promotion.latency").register(registry);
        this.claimAge = DistributionSummary.builder("promotion.claim_age")
                .baseUnit("milliseconds").register(registry);
    }

    @Scheduled(fixedDelayString = "${relix.promotion-consumer.poll-delay-ms:10000}")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        String tenant = properties.tenant();
        if (tenant == null || tenant.isBlank()) {
            log.warn("Promotion consumer is enabled without a tenant; skipping poll");
            return;
        }
        Instant now = clock.instant();
        try {
            claims.reclaimExpiredPromotions(tenant, now, properties.batchRelationsOrDefault());
        } catch (Exception e) {
            log.warn("Promotion lease reclaim failed tenant={}: {}", tenant, e.toString());
        }
        List<RelationPromotionWorkRow> pending;
        try {
            pending = claims.pendingPromotions(tenant, properties.batchRelationsOrDefault(), now);
        } catch (Exception e) {
            log.warn("Promotion poll failed tenant={}: {}", tenant, e.toString());
            return;
        }
        for (RelationPromotionWorkRow work : pending) {
            process(work);
            completeCanonicalIfTerminal(work);
        }
    }

    private void process(RelationPromotionWorkRow work) {
        Instant now = clock.instant();
        try {
            if (!claims.claimPromotion(work, workerId, now,
                    Duration.ofSeconds(properties.leaseSecondsOrDefault()))) {
                return;
            }
            claimed.increment();
            if (work.result().decidedAt() != null) {
                claimAge.record(Math.max(0,
                        Duration.between(work.result().decidedAt(), now).toMillis()));
            }
        } catch (Exception e) {
            log.warn("Promotion claim failed relation={}: {}",
                    work.result().relationId(), e.toString());
            return;
        }

        Instant claimedAt = now;
        try {
            CanonicalRelation relation = canonicalRelation(work.result());
            promotionPort.promoteRelation(relation);
            if (!claims.completePromotion(work, workerId, clock.instant())) {
                log.warn("Promotion completion lost ownership relation={}",
                        work.result().relationId());
                return;
            }
            completed.increment();
            registry.counter("promotion.outcome", "outcome", "PROMOTED").increment();
        } catch (ContractViolationException e) {
            failed.increment();
            String outcome = e instanceof StaleGenerationException ? "STALE_GENERATION"
                    : e instanceof UnauthorizedGenerationException ? "UNAUTHORIZED_GENERATION"
                    : "CONTRACT_VIOLATION";
            registry.counter("promotion.outcome", "outcome", outcome).increment();
            claims.releasePromotion(work, workerId, FailureClass.CONTRACT_VIOLATION,
                    e.getMessage(), properties.maxAttemptsOrDefault(),
                    properties.retryBackoffSecondsOrDefault(), clock.instant());
            log.warn("Promotion contract failure relation={} outcome={}: {}",
                    work.result().relationId(), outcome, e.toString());
        } catch (Exception e) {
            FailureClass failureClass = FailureClassifier.classify(e);
            failed.increment();
            boolean attemptExhausted = failureClass == FailureClass.INFRASTRUCTURE_RETRYABLE
                    && work.attemptCount() + 1 >= properties.maxAttemptsOrDefault();
            String outcome = e instanceof FenceBehindException ? "FENCE_BEHIND"
                    : attemptExhausted ? "EXHAUSTED_RETRY"
                    : isAbortRecoverable(e) ? "ABORT_RECOVERABLE"
                    : failureClass == FailureClass.INFRASTRUCTURE_RETRYABLE ? "RETRYABLE"
                    : "FAILED";
            registry.counter("promotion.outcome", "outcome", outcome).increment();
            if (attemptExhausted) {
                exhausted.increment();
            } else if (failureClass == FailureClass.INFRASTRUCTURE_RETRYABLE) {
                registry.counter("promotion.retried").increment();
            }
            claims.releasePromotion(work, workerId, failureClass, e.toString(),
                    properties.maxAttemptsOrDefault(),
                    properties.retryBackoffSecondsOrDefault(), clock.instant());
            log.warn("Promotion failure relation={} class={} outcome={}: {}",
                    work.result().relationId(), failureClass, outcome, e.toString());
        } finally {
            latency.record(Duration.between(claimedAt, clock.instant()));
        }
    }

    private void completeCanonicalIfTerminal(RelationPromotionWorkRow work) {
        if (completion == null) {
            return;
        }
        try {
            completion.completeIfPromotionTerminal(work.result());
        } catch (Exception e) {
            log.warn("Canonical graph completion check failed relation={}: {}",
                    work.result().relationId(), e.toString());
        }
    }

    private static boolean isAbortRecoverable(Exception failure) {
        return failure instanceof RetryableProcessingException retryable
                && retryable.reason() == RetryReason.TRANSACTION_ABORTED;
    }

    private CanonicalRelation canonicalRelation(RelationPromotionResultRow row) {
        if (row.canonicalSubjectId() == null || row.canonicalObjectId() == null
                || row.evidenceJson() == null || row.evidenceJson().isBlank()) {
            throw new ContractViolationException(
                    "VALID promotion row is missing canonical ids or evidence");
        }
        JsonNode view;
        try {
            view = mapper.readTree(row.evidenceJson()).path("view");
        } catch (Exception e) {
            throw new ContractViolationException("Could not parse promotion evidence", e);
        }
        if (view.isMissingNode() || view.isNull()) {
            throw new ContractViolationException("Promotion evidence has no extracted relation view");
        }
        String evidenceChunkId = view.path("evidenceChunkId").asText(null);
        if (evidenceChunkId == null || evidenceChunkId.isBlank()) {
            throw new ContractViolationException("Promotion evidence has no chunk id");
        }
        return new CanonicalRelation(
                row.tenantId(), row.sourceVersionId().toString(), row.relationId(),
                row.subjectMentionId(), row.canonicalSubjectId(),
                row.objectMentionId(), row.canonicalObjectId(), row.predicate(),
                row.ontologyVersion(), evidenceChunkId,
                view.path("evidenceChunkOffsetStart").asInt(-1),
                view.path("evidenceChunkOffsetEnd").asInt(-1),
                row.sourceId() != null ? row.sourceId() : view.path("sourceId").asText(null),
                row.revisionId() != null ? row.revisionId().toString()
                        : view.path("revisionId").asText(row.sourceVersionId().toString()),
                row.generation() > 0 ? row.generation() : view.path("generation").asLong(0),
                row.extractionRunId() != null ? row.extractionRunId()
                        : view.path("extractionRunId").asText(null));
    }
}
