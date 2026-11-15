package org.synanton.ingestioncache.backfill;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.synanton.ingestioncache.activation.ActivationState;
import org.synanton.ingestioncache.activation.CassandraSourceActivationStore;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.ingestioncache.domain.SourceIdentityRow;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;

/**
 * #699 one-time visibility backfill and dry-run.
 *
 * <p>The search read path resolves a chunk's supporting source versions and
 * checks each source's authoritative current revision. Pre-#654 data can lack a
 * {@code source_current} pointer, which the read path currently treats as legacy
 * fail-open. This tool reports (and optionally repairs) those mappings before the
 * fail-closed switch.
 *
 * <p>It never deletes or mutates chunks. {@code --apply} only creates missing
 * {@code source_current} rows for sources with a valid current candidate
 * (activation CURRENT, or the highest GRAPH_COMMITTED legacy revision when no
 * activation row exists). Sources without a valid candidate are reported as
 * would-be-hidden; the fail-closed switch decides their visibility.
 */
public final class SourceVisibilityBackfill {

    private static final Set<String> INDEXABLE_STATES = Set.of("CHUNKED", "ENRICHED", "EMBEDDED");

    private SourceVisibilityBackfill() {}

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        String host = env("CASSANDRA_HOST", "localhost");
        int port = Integer.parseInt(env("CASSANDRA_PORT", "9042"));
        String localDc = env("CASSANDRA_LOCAL_DC", "datacenter1");
        try (CqlSession session = CqlSession.builder()
                .addContactPoint(new InetSocketAddress(host, port))
                .withLocalDatacenter(localDc)
                .withKeyspace("ingestion_cache")
                .build()) {
            new Runner(session, options).run();
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private record Options(String tenant, boolean apply, boolean audit, Path checkpoint) {
        static Options parse(String[] args) {
            String tenant = "demo";
            boolean apply = false;
            boolean audit = false;
            Path checkpoint = Path.of("/tmp/synanton-visibility-backfill.checkpoint");
            for (String arg : args) {
                if (arg.startsWith("--tenant=")) {
                    tenant = arg.substring("--tenant=".length());
                } else if (arg.equals("--apply")) {
                    apply = true;
                } else if (arg.equals("--dry-run")) {
                    apply = false;
                } else if (arg.equals("--audit")) {
                    audit = true;
                    apply = false;
                } else if (arg.startsWith("--checkpoint=")) {
                    checkpoint = Path.of(arg.substring("--checkpoint=".length()));
                }
            }
            return new Options(tenant, apply, audit, checkpoint);
        }
    }

    private static final class Runner {

        private final CqlSession session;
        private final IngestionCacheClient cache;
        private final CassandraSourceActivationStore activations;
        private final Options options;

        private final Map<UUID, SourceVersionRow> versionsById = new HashMap<>();
        private final Map<String, List<SourceVersionRow>> versionsBySource = new HashMap<>();
        private final Map<String, UUID> currentBySource = new HashMap<>();
        private final Map<String, SourceVersionRow> repairCandidateBySource = new HashMap<>();
        private final Map<String, Boolean> tombstoned = new HashMap<>();
        private final Map<UUID, Set<UUID>> versionContentRefs = new HashMap<>();
        private final Map<String, List<SupersededSample>> supersededSamplesBySource = new HashMap<>();

        private record SupersededSample(
                String chunkId, String sourceId, UUID indexedRevision, UUID currentRevision,
                Instant indexedObservedAt, Instant currentObservedAt,
                long indexedGeneration, long currentGeneration) {}

        private final AtomicInteger total = new AtomicInteger();
        private final AtomicInteger visible = new AtomicInteger();
        private final AtomicInteger repairable = new AtomicInteger();
        private final Map<String, Integer> hidden = new LinkedHashMap<>();
        private final Map<String, List<String>> examples = new LinkedHashMap<>();

        private Runner(CqlSession session, Options options) {
            this.session = session;
            this.cache = new IngestionCacheClient(session);
            this.activations = new CassandraSourceActivationStore(session);
            this.options = options;
            hidden.put("unmapped", 0);
            hidden.put("deleted", 0);
            hidden.put("no_current", 0);
            hidden.put("superseded", 0);
            examples.put("unmapped", new ArrayList<>());
            examples.put("deleted", new ArrayList<>());
            examples.put("no_current", new ArrayList<>());
            examples.put("superseded", new ArrayList<>());
        }

        private void run() throws Exception {
            loadSources();
            collectVersionChunkPresence();
            computeRepairCandidates();
            scanChunks();
            printReport();
            if (options.audit()) {
                printAudit();
            }
            if (options.apply() && !options.audit()) {
                applyRepairs();
            } else {
                System.out.println("\n" + (options.audit() ? "audit only" : "dry-run only")
                        + "; no source_current rows written");
            }
        }

        private void loadSources() {
            List<SourceRow> sources = cache.listSources(options.tenant());
            if (sources == null || sources.isEmpty()) {
                return;
            }
            sources.stream()
                    .sorted(Comparator.comparing(SourceRow::sourceId))
                    .forEach(source -> {
                        List<SourceIdentityRow> identities = cache.listSourceIdentities(
                                options.tenant(), source.sourceId(), source.connectorId());
                        if (identities == null) {
                            return;
                        }
                        List<SourceVersionRow> sourceVersions = new ArrayList<>();
                        for (SourceIdentityRow identity : identities) {
                            List<SourceVersionRow> rows = cache.listSourceVersions(
                                    options.tenant(), identity.connectorId(),
                                    identity.sourceId(), identity.resourceId());
                            if (rows != null) {
                                sourceVersions.addAll(rows);
                            }
                        }
                        for (SourceVersionRow version : sourceVersions) {
                            versionsById.put(version.versionId(), version);
                        }
                        versionsBySource.put(source.sourceId(), sourceVersions);
                        cache.readCurrentRevisionId(options.tenant(), source.sourceId())
                                .ifPresent(revision -> currentBySource.put(source.sourceId(), revision));
                    });
        }

        private void collectVersionChunkPresence() {
            for (ManifestRow manifest : cache.manifestCursor(options.tenant(), 200_000)) {
                if (!INDEXABLE_STATES.contains(
                        manifest.state() == null ? "" : manifest.state().toUpperCase(Locale.ROOT))) {
                    continue;
                }
                for (ChunkRow chunk : cache.readChunks(options.tenant(), manifest.contentRefId())) {
                    if (chunk.sourceVersionIds() == null) {
                        continue;
                    }
                    for (UUID versionId : chunk.sourceVersionIds()) {
                        versionContentRefs
                                .computeIfAbsent(versionId, ignored -> new java.util.HashSet<>())
                                .add(manifest.contentRefId());
                    }
                }
            }
        }

        private void computeRepairCandidates() {
            for (Map.Entry<String, List<SourceVersionRow>> entry : versionsBySource.entrySet()) {
                if (currentBySource.containsKey(entry.getKey())) {
                    continue;
                }
                SourceVersionRow candidate = repairCandidate(entry.getKey(), entry.getValue());
                if (candidate != null) {
                    repairCandidateBySource.put(entry.getKey(), candidate);
                }
            }
        }

        private SourceVersionRow repairCandidate(String sourceId, List<SourceVersionRow> sourceVersions) {
            List<SourceVersionRow> ordered = sourceVersions.stream()
                    .sorted(Comparator.comparingLong(SourceVersionRow::generation).reversed()
                            .thenComparing(SourceVersionRow::observedAt,
                                    Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
            boolean anyActivation = false;
            for (SourceVersionRow version : ordered) {
                Optional<org.synanton.ingestioncache.activation.ActivationRecord> activation =
                        activations.read(options.tenant(), sourceId, version.generation());
                if (activation.isPresent()) {
                    anyActivation = true;
                    if (activation.get().state() == ActivationState.CURRENT) {
                        return version;
                    }
                }
            }
            // A source with an activation history but no CURRENT record is failed or
            // in progress; do not fabricate a current pointer for it.
            if (anyActivation) {
                return null;
            }
            // Legacy pre-#654 sources have no activation row. Prefer a graph-committed
            // revision; otherwise the highest observed indexed revision is the legacy
            // current candidate.
            for (SourceVersionRow version : ordered) {
                if (versionContentRefs.containsKey(version.versionId())
                        && cache.readGraphStatus(options.tenant(), version.connectorId(), sourceId,
                                version.resourceId(), version.versionId())
                                .filter("GRAPH_COMMITTED"::equals)
                                .isPresent()) {
                    return version;
                }
            }
            for (SourceVersionRow version : ordered) {
                if (versionContentRefs.containsKey(version.versionId())) {
                    return version;
                }
            }
            return null;
        }

        private void scanChunks() {
            for (ManifestRow manifest : cache.manifestCursor(options.tenant(), 200_000)) {
                if (!INDEXABLE_STATES.contains(
                        manifest.state() == null ? "" : manifest.state().toUpperCase(Locale.ROOT))) {
                    continue;
                }
                for (ChunkRow chunk : cache.readChunks(options.tenant(), manifest.contentRefId())) {
                    total.incrementAndGet();
                    classify(chunk);
                }
            }
        }

        private void classify(ChunkRow chunk) {
            String chunkId = chunk.contentRefId() + ":" + chunk.chunkOrdinal();
            Set<UUID> versionIds = chunk.sourceVersionIds();
            if (versionIds == null || versionIds.isEmpty()) {
                hide("unmapped", chunkId, null, "no chunk_source_versions attribution");
                return;
            }
            boolean anyCurrent = false;
            boolean anyRepairable = false;
            boolean anyDeleted = false;
            boolean anyNoCurrent = false;
            boolean anySuperseded = false;
            String sourceId = null;
            String unmappedReason = null;
            UUID supersededVersionId = null;
            UUID supersededCurrentId = null;
            for (UUID versionId : versionIds) {
                SourceVersionRow version = versionsById.get(versionId);
                if (version == null) {
                    unmappedReason = "source version " + versionId + " not found";
                    continue;
                }
                sourceId = version.sourceId();
                if (isTombstoned(version)) {
                    anyDeleted = true;
                    continue;
                }
                UUID current = currentBySource.get(version.sourceId());
                if (current != null) {
                    if (current.equals(versionId)) {
                        anyCurrent = true;
                    } else {
                        anySuperseded = true;
                        if (supersededVersionId == null) {
                            supersededVersionId = versionId;
                            supersededCurrentId = current;
                        }
                    }
                } else {
                    SourceVersionRow candidate = repairCandidateBySource.get(version.sourceId());
                    if (candidate == null) {
                        anyNoCurrent = true;
                    } else if (candidate.versionId().equals(versionId)) {
                        anyRepairable = true;
                    } else {
                        // Older than the legacy current candidate: stale, not unmapped.
                        anySuperseded = true;
                        if (supersededVersionId == null) {
                            supersededVersionId = versionId;
                            supersededCurrentId = candidate.versionId();
                        }
                    }
                }
            }
            if (anyCurrent) {
                visible.incrementAndGet();
                return;
            }
            if (unmappedReason != null) {
                hide("unmapped", chunkId, sourceId, unmappedReason);
            } else if (anyDeleted) {
                hide("deleted", chunkId, sourceId, "source/resource has a tombstone");
            } else if (anyRepairable) {
                repairable.incrementAndGet();
            } else if (anyNoCurrent) {
                hide("no_current", chunkId, sourceId, "source has no current revision pointer");
            } else if (anySuperseded) {
                recordSupersededSample(chunkId, sourceId, supersededVersionId, supersededCurrentId);
                hide("superseded", chunkId, sourceId, "supporting revision is not current");
            } else {
                hide("unmapped", chunkId, sourceId, "no resolvable current support");
            }
        }

        private void recordSupersededSample(String chunkId, String sourceId,
                                            UUID indexedRevision, UUID currentRevision) {
            if (sourceId == null || indexedRevision == null || currentRevision == null) {
                return;
            }
            SourceVersionRow indexed = versionsById.get(indexedRevision);
            SourceVersionRow current = versionsById.get(currentRevision);
            if (indexed == null) {
                return;
            }
            supersededSamplesBySource.computeIfAbsent(sourceId, ignored -> new ArrayList<>())
                    .add(new SupersededSample(chunkId, sourceId, indexedRevision, currentRevision,
                            indexed.observedAt(), current == null ? null : current.observedAt(),
                            indexed.generation(), current == null ? 0 : current.generation()));
        }

        private boolean isTombstoned(SourceVersionRow version) {
            String key = version.connectorId() + "|" + version.sourceId() + "|" + version.resourceId();
            return tombstoned.computeIfAbsent(key, ignored ->
                    cache.readLatestSourceTombstone(options.tenant(), version.connectorId(),
                            version.sourceId(), version.resourceId()).isPresent());
        }

        private void hide(String category, String chunkId, String sourceId, String reason) {
            hidden.merge(category, 1, Integer::sum);
            List<String> rows = examples.get(category);
            if (rows.size() < 10) {
                rows.add(chunkId + " source=" + (sourceId == null ? "?" : sourceId) + " reason=" + reason);
            }
        }

        private void printReport() {
            int wouldHide = hidden.values().stream().mapToInt(Integer::intValue).sum();
            System.out.println("tenant=" + options.tenant());
            System.out.println("sources_total=" + versionsBySource.size());
            System.out.println("sources_with_current=" + currentBySource.size());
            System.out.println("repairable_sources=" + repairCandidateBySource.size());
            System.out.println("total_indexed_chunks=" + total.get());
            System.out.println("visible_current=" + visible.get());
            System.out.println("repairable_by_backfill=" + repairable.get());
            System.out.println("would_hide_after_backfill=" + wouldHide);
            System.out.println("  hidden_unmapped=" + hidden.get("unmapped"));
            System.out.println("  hidden_deleted=" + hidden.get("deleted"));
            System.out.println("  hidden_no_current=" + hidden.get("no_current"));
            System.out.println("  hidden_superseded=" + hidden.get("superseded"));
            System.out.println("top_hidden_examples:");
            for (Map.Entry<String, List<String>> entry : examples.entrySet()) {
                for (String example : entry.getValue()) {
                    System.out.println("  [" + entry.getKey() + "] " + example);
                }
            }
        }

        private void printAudit() {
            System.out.println("\n=== #699 audit table 1: repairable candidates ===");
            System.out.println("| source_id | selected_revision | observed_at | status | content_ref_id "
                    + "| latest_completed | is_latest_completed | latest_indexed | is_latest_indexed | safe |");
            boolean unsafe = false;
            int legacy = 0;
            for (String sourceId : repairCandidateBySource.keySet().stream().sorted().toList()) {
                SourceVersionRow candidate = repairCandidateBySource.get(sourceId);
                String status = status(candidate);
                SourceVersionRow latest = latestCompleted(sourceId);
                SourceVersionRow latestIndexed = latestIndexed(sourceId);
                UUID contentRef = candidate.contentRefId();
                if (contentRef == null) {
                    Set<UUID> refs = versionContentRefs.get(candidate.versionId());
                    if (refs != null && !refs.isEmpty()) {
                        contentRef = refs.iterator().next();
                    }
                }
                boolean candidateSafe = !status.contains("FAILED")
                        && !status.contains("IN_PROGRESS")
                        && !status.contains("PENDING");
                String latestId;
                String isLatest;
                if (latest == null) {
                    legacy++;
                    latestId = "LEGACY/UNKNOWN";
                    isLatest = "legacy";
                } else {
                    latestId = latest.versionId().toString();
                    isLatest = String.valueOf(latest.versionId().equals(candidate.versionId()));
                    if (!latest.versionId().equals(candidate.versionId())) {
                        candidateSafe = false;
                    }
                }
                String latestIndexedId = latestIndexed == null
                        ? "n/a" : latestIndexed.versionId().toString();
                String isLatestIndexed = latestIndexed == null
                        ? "n/a" : String.valueOf(latestIndexed.versionId().equals(candidate.versionId()));
                if (latestIndexed != null && !latestIndexed.versionId().equals(candidate.versionId())) {
                    candidateSafe = false;
                }
                if (!candidateSafe) {
                    unsafe = true;
                }
                System.out.println("| " + sourceId + " | " + candidate.versionId() + " | "
                        + candidate.observedAt() + " | " + status + " | " + contentRef + " | "
                        + latestId + " | " + isLatest + " | " + latestIndexedId + " | "
                        + isLatestIndexed + " | " + candidateSafe + " |");
            }
            if (unsafe) {
                System.out.println("AUDIT: UNSAFE CANDIDATES PRESENT; DO NOT APPLY");
            } else if (legacy > 0) {
                System.out.println("AUDIT: " + legacy
                        + " legacy candidates have no completed-status row; review required");
            } else {
                System.out.println("AUDIT: all candidates safe");
            }

            System.out.println("\n=== #699 audit table 2: superseded stratified sample ===");
            System.out.println("| chunk_id | source_id | indexed_revision | current_revision "
                    + "| indexed_observed_at | current_observed_at | is_older |");
            List<String> sources = new ArrayList<>(supersededSamplesBySource.keySet());
            Collections.sort(sources);
            Collections.shuffle(sources, new Random(699));
            int chosen = 0;
            int printed = 0;
            for (String sourceId : sources) {
                if (printed >= 10) {
                    break;
                }
                chosen++;
                List<SupersededSample> samples = new ArrayList<>(supersededSamplesBySource.get(sourceId));
                samples.sort(Comparator.comparing(SupersededSample::chunkId));
                Collections.shuffle(samples, new Random(699 + chosen));
                for (int i = 0; i < Math.min(2, samples.size()) && printed < 10; i++) {
                    SupersededSample sample = samples.get(i);
                    System.out.println("| " + sample.chunkId() + " | " + sample.sourceId() + " | "
                            + sample.indexedRevision() + " | " + sample.currentRevision() + " | "
                            + sample.indexedObservedAt() + " | " + sample.currentObservedAt() + " | "
                            + isOlder(sample) + " |");
                    printed++;
                }
            }
            System.out.println("AUDIT: sampled " + printed + " chunk(s) from " + chosen
                    + " source(s) of " + supersededSamplesBySource.size() + " with superseded chunks");
        }

        private String status(SourceVersionRow version) {
            String extraction = cache.readExtractionStatus(options.tenant(), version.connectorId(),
                    version.sourceId(), version.resourceId(), version.versionId()).orElse("UNKNOWN");
            String graph = cache.readGraphStatus(options.tenant(), version.connectorId(),
                    version.sourceId(), version.resourceId(), version.versionId()).orElse("UNKNOWN");
            return extraction + "/" + graph;
        }

        private SourceVersionRow latestCompleted(String sourceId) {
            return versionsBySource.getOrDefault(sourceId, List.of()).stream()
                    .filter(version -> "KNOWLEDGE_READY".equals(cache.readExtractionStatus(
                            options.tenant(), version.connectorId(), version.sourceId(),
                            version.resourceId(), version.versionId()).orElse(null)))
                    .max(Comparator.comparing(SourceVersionRow::observedAt,
                            Comparator.nullsFirst(Comparator.naturalOrder())))
                    .orElse(null);
        }

        private SourceVersionRow latestIndexed(String sourceId) {
            return versionsBySource.getOrDefault(sourceId, List.of()).stream()
                    .filter(version -> versionContentRefs.containsKey(version.versionId()))
                    .max(Comparator.comparing(SourceVersionRow::observedAt,
                            Comparator.nullsFirst(Comparator.naturalOrder())))
                    .orElse(null);
        }

        private String isOlder(SupersededSample sample) {
            if (sample.indexedObservedAt() != null && sample.currentObservedAt() != null) {
                return String.valueOf(sample.indexedObservedAt().isBefore(sample.currentObservedAt()));
            }
            if (sample.indexedGeneration() != sample.currentGeneration()) {
                return String.valueOf(sample.indexedGeneration() < sample.currentGeneration());
            }
            return "unknown";
        }

        private void applyRepairs() throws Exception {
            List<String> sourceIds = repairCandidateBySource.keySet().stream().sorted().toList();
            String last = readCheckpoint();
            int applied = 0;
            for (String sourceId : sourceIds) {
                if (last != null && sourceId.compareTo(last) <= 0) {
                    continue;
                }
                SourceVersionRow candidate = repairCandidateBySource.get(sourceId);
                long accepted = Math.max(candidate.generation(),
                        cache.readAcceptedGeneration(options.tenant(), sourceId)
                                .orElse(candidate.generation()));
                session.execute(SimpleStatement.newInstance(
                        "UPDATE ingestion_cache.source_current SET accepted_generation=?, "
                                + "current_generation=?, active_revision_id=?, pending_generation=null, "
                                + "pending_revision_id=null, updated_at=? "
                                + "WHERE tenant_id=? AND source_id=?",
                        accepted, candidate.generation(), candidate.versionId(),
                        Instant.now(), options.tenant(), sourceId));
                applied++;
                writeCheckpoint(sourceId);
                System.out.println("repaired source_current source=" + sourceId
                        + " generation=" + candidate.generation()
                        + " revision=" + candidate.versionId());
            }
            System.out.println("repaired_sources=" + applied);
        }

        private String readCheckpoint() throws Exception {
            if (!Files.exists(options.checkpoint())) {
                return null;
            }
            String value = Files.readString(options.checkpoint()).trim();
            return value.isEmpty() ? null : value;
        }

        private void writeCheckpoint(String sourceId) throws Exception {
            Path parent = options.checkpoint().toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(options.checkpoint(), sourceId + "\n");
        }
    }
}
