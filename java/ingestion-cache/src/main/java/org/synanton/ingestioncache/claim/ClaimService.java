package org.synanton.ingestioncache.claim;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.RelationPromotionWorkRow;
import org.synanton.ingestioncache.domain.ResolutionWorkRow;
import org.synanton.ingestioncache.domain.SourceIdentityRow;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;
import org.synanton.common.resolution.FailureClass;
import org.synanton.common.resolution.ResolutionDecision;
import org.synanton.common.resolution.ResolutionLifecycle;
import org.synanton.common.resolution.ResolutionOutcome;
import org.springframework.stereotype.Component;

/**
 * Shared claim abstraction for the resolution and promotion consumers
 * (PR #4d.1). Rows are owned through an LWT, recovered only by their exact
 * owner after lease expiry, and never completed by a worker that lost its
 * claim.
 */
@Component
public class ClaimService {

    private final IngestionCacheClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public ClaimService(IngestionCacheClient client) {
        this.client = client;
    }

    public List<ResolutionWorkRow> pendingResolutions(String tenantId, int limit, Instant now) {
        List<ResolutionWorkRow> pending = new ArrayList<>();
        for (ResolutionWorkRow row : resolutionWork(tenantId)) {
            if (row.claimable(now)) {
                pending.add(row);
            }
        }
        return pending.stream()
                .sorted(Comparator.comparing(ResolutionWorkRow::workCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ResolutionWorkRow::mentionId))
                .limit(Math.max(1, limit)).toList();
    }

    public List<ResolutionWorkRow> expiredResolutions(String tenantId, Instant now, int limit) {
        return resolutionWork(tenantId).stream()
                .filter(row -> "PROCESSING".equals(row.claimStatus()))
                .filter(row -> row.leaseExpiresAt() != null && row.leaseExpiresAt().isBefore(now))
                .limit(Math.max(1, limit)).toList();
    }

    public int reclaimExpiredResolutions(String tenantId, Instant now, int limit) {
        int reclaimed = 0;
        for (ResolutionWorkRow row : expiredResolutions(tenantId, now, limit)) {
            if (client.reclaimResolutionWork(row, now)) {
                reclaimed++;
            }
        }
        return reclaimed;
    }

    /** All tenant work rows, including terminal rows; used by crash reconciliation. */
    public List<ResolutionWorkRow> resolutionWorkForTenant(String tenantId) {
        return resolutionWork(tenantId);
    }

    public boolean claimResolution(ResolutionWorkRow row, String ownerId,
                                   Instant now, Duration lease) {
        return client.claimResolutionWork(row, ownerId, now, lease);
    }

    /** Reclaim exactly one expired lease from its old owner (PR #578). */
    public boolean reclaimResolution(ResolutionWorkRow row, Instant now) {
        return client.reclaimResolutionWork(row, now);
    }

    public boolean completeResolution(ResolutionWorkRow row, String ownerId,
                                      ResolutionDecision decision, Instant now) {
        String diagnostics;
        try {
            diagnostics = mapper.writeValueAsString(decision.diagnostics());
        } catch (Exception e) {
            diagnostics = "{}";
        }
        return client.completeResolutionWork(row, ownerId, decision.outcome().name(),
                decision.reasonCode(), decision.resolvedEntityId(), decision.canonicalLabel(),
                decision.bestScore(), decision.runnerUpScore(), decision.strategyVersion(),
                decision.ontologyVersion(), diagnostics, decision.searchGeneration(), now);
    }

    public boolean releaseResolution(ResolutionWorkRow row, String ownerId,
                                     FailureClass failureClass, String error,
                                     int maxAttempts, long backoffSeconds, Instant now) {
        int attempt = row.attemptCount() + 1;
        boolean retryable = failureClass == FailureClass.INFRASTRUCTURE_RETRYABLE
                && attempt < Math.max(1, maxAttempts);
        String status = retryable ? "PENDING" : "FAILED";
        Instant nextAttempt = retryable ? now.plusSeconds(backoff(backoffSeconds, attempt)) : null;
        return client.releaseResolutionWork(row, ownerId, status, failureClass.name(),
                truncate(error), nextAttempt);
    }

    public boolean supersedeResolution(ResolutionWorkRow row, String ownerId,
                                       String error, Instant now) {
        return client.supersedeResolutionWork(row, ownerId, truncate(error), now);
    }

    public List<RelationPromotionWorkRow> pendingPromotions(String tenantId, int limit, Instant now) {
        List<RelationPromotionWorkRow> pending = new ArrayList<>();
        for (RelationPromotionWorkRow work : promotionWork(tenantId)) {
            if (work.claimable(now)) {
                pending.add(work);
            }
        }
        return pending.stream()
                .sorted(Comparator.comparing(
                                (RelationPromotionWorkRow work) -> work.result().decidedAt(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(work -> work.result().relationId()))
                .limit(Math.max(1, limit)).toList();
    }

    public List<RelationPromotionWorkRow> expiredPromotions(String tenantId, Instant now, int limit) {
        return promotionWork(tenantId).stream()
                .filter(work -> "PROCESSING".equals(work.claimStatus()))
                .filter(work -> work.leaseExpiresAt() != null && work.leaseExpiresAt().isBefore(now))
                .limit(Math.max(1, limit)).toList();
    }

    public int reclaimExpiredPromotions(String tenantId, Instant now, int limit) {
        int reclaimed = 0;
        for (RelationPromotionWorkRow work : expiredPromotions(tenantId, now, limit)) {
            if (client.reclaimPromotionWork(work, now)) {
                reclaimed++;
            }
        }
        return reclaimed;
    }

    public boolean claimPromotion(RelationPromotionWorkRow work, String ownerId,
                                  Instant now, Duration lease) {
        return client.claimPromotionWork(work, ownerId, now, lease);
    }

    public boolean completePromotion(RelationPromotionWorkRow work, String ownerId, Instant now) {
        return client.completePromotionWork(work, ownerId, now);
    }

    public boolean releasePromotion(RelationPromotionWorkRow work, String ownerId,
                                    FailureClass failureClass, String error,
                                    int maxAttempts, long backoffSeconds, Instant now) {
        int attempt = work.attemptCount() + 1;
        boolean retryable = failureClass == FailureClass.INFRASTRUCTURE_RETRYABLE
                && attempt < Math.max(1, maxAttempts);
        String status = retryable ? "PENDING" : "FAILED";
        Instant nextAttempt = retryable ? now.plusSeconds(backoff(backoffSeconds, attempt)) : null;
        return client.releasePromotionWork(work, ownerId, status, failureClass.name(),
                truncate(error), nextAttempt);
    }

    /**
     * Derives the version lifecycle from every mention row: any pending work is
     * PENDING, an in-flight row is RESOLVING, permanent failure is FAILED, a
     * REVIEW_REQUIRED decision is PARTIAL, and all terminal outcomes COMPLETED.
     */
    public void refreshVersionStatus(ResolutionWorkRow row, Instant now) {
        if (row.connectorId() == null || row.sourceId() == null || row.resourceId() == null) {
            return;
        }
        List<ResolutionWorkRow> works = client.listResolutionWorkRows(
                row.tenantId(), row.sourceVersionId());
        if (works.isEmpty()) {
            return;
        }
        int pending = 0;
        int processing = 0;
        int failed = 0;
        int review = 0;
        int resolved = 0;
        int ambiguous = 0;
        int unresolved = 0;
        int superseded = 0;
        Instant startedAt = null;
        for (ResolutionWorkRow work : works) {
            String status = work.claimStatus();
            if (status == null) {
                // Pre-#4d.1 decision rows have no claim lifecycle; their outcome is terminal.
                status = work.outcome() == null ? null : "COMPLETED";
            }
            if (status == null) {
                continue;
            }
            switch (status) {
                case "PENDING" -> pending++;
                case "PROCESSING" -> processing++;
                case "FAILED" -> failed++;
                case "SUPERSEDED" -> superseded++;
                default -> {
                    String outcome = work.outcome();
                    if (ResolutionOutcome.REVIEW_REQUIRED.name().equals(outcome)) {
                        review++;
                    } else if (ResolutionOutcome.RESOLVED.name().equals(outcome)) {
                        resolved++;
                    } else if (ResolutionOutcome.AMBIGUOUS.name().equals(outcome)) {
                        ambiguous++;
                    } else if (ResolutionOutcome.UNRESOLVED.name().equals(outcome)) {
                        unresolved++;
                    }
                }
            }
            Instant claimed = work.claimedAt() != null ? work.claimedAt() : work.workCreatedAt();
            if (claimed != null && (startedAt == null || claimed.isBefore(startedAt))) {
                startedAt = claimed;
            }
        }
        ResolutionLifecycle lifecycle;
        if (processing > 0) {
            lifecycle = ResolutionLifecycle.RESOLVING;
        } else if (pending > 0) {
            lifecycle = ResolutionLifecycle.PENDING;
        } else if (failed > 0) {
            lifecycle = ResolutionLifecycle.FAILED;
        } else if (review > 0) {
            lifecycle = ResolutionLifecycle.PARTIAL;
        } else {
            lifecycle = ResolutionLifecycle.COMPLETED;
        }
        Instant completedAt = lifecycle == ResolutionLifecycle.COMPLETED
                || lifecycle == ResolutionLifecycle.PARTIAL
                || lifecycle == ResolutionLifecycle.FAILED ? now : null;
        Map<String, Integer> counters = new LinkedHashMap<>();
        counters.put("pending", pending);
        counters.put("processing", processing);
        counters.put("failed", failed);
        counters.put("superseded", superseded);
        counters.put("resolved", resolved);
        counters.put("ambiguous", ambiguous);
        counters.put("unresolved", unresolved);
        counters.put("review_required", review);
        client.markResolutionStatus(row.tenantId(), row.connectorId(), row.sourceId(),
                row.resourceId(), row.sourceVersionId(), lifecycle.name(), startedAt,
                completedAt, counters);
    }

    private List<ResolutionWorkRow> resolutionWork(String tenantId) {
        List<ResolutionWorkRow> rows = new ArrayList<>();
        for (UUID version : tenantVersions(tenantId)) {
            rows.addAll(client.listResolutionWorkRows(tenantId, version));
        }
        return rows;
    }

    private List<RelationPromotionWorkRow> promotionWork(String tenantId) {
        List<RelationPromotionWorkRow> rows = new ArrayList<>();
        for (UUID version : tenantVersions(tenantId)) {
            rows.addAll(client.listRelationPromotionWorkRows(tenantId, version));
        }
        return rows;
    }

    private Set<UUID> tenantVersions(String tenantId) {
        Set<UUID> versions = new LinkedHashSet<>();
        for (SourceRow source : client.listSources(tenantId)) {
            for (SourceIdentityRow identity : client.listSourceIdentities(
                    tenantId, source.sourceId(), source.connectorId())) {
                for (SourceVersionRow version : client.listSourceVersions(
                        tenantId, identity.connectorId(), identity.sourceId(), identity.resourceId())) {
                    versions.add(version.versionId());
                }
            }
        }
        return versions;
    }

    private static long backoff(long baseSeconds, int attempt) {
        long base = Math.max(1, baseSeconds);
        long multiplier = 1L << Math.min(Math.max(0, attempt - 1), 5);
        return Math.min(300, base * multiplier);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
