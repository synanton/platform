package org.synanton.ingestioncache.backfill;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    private record Options(String tenant, boolean apply, Path checkpoint) {
        static Options parse(String[] args) {
            String tenant = "demo";
            boolean apply = false;
            Path checkpoint = Path.of("/tmp/synanton-visibility-backfill.checkpoint");
            for (String arg : args) {
                if (arg.startsWith("--tenant=")) {
                    tenant = arg.substring("--tenant=".length());
                } else if (arg.equals("--apply")) {
                    apply = true;
                } else if (arg.equals("--dry-run")) {
                    apply = false;
                } else if (arg.startsWith("--checkpoint=")) {
                    checkpoint = Path.of(arg.substring("--checkpoint=".length()));
                }
            }
            return new Options(tenant, apply, checkpoint);
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
        private final Map<UUID, Boolean> versionHasChunks = new HashMap<>();

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
            if (options.apply()) {
                applyRepairs();
            } else {
                System.out.println("\ndry-run only; no source_current rows written");
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
                        versionHasChunks.put(versionId, true);
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
                if (versionHasChunks.containsKey(version.versionId())
                        && cache.readGraphStatus(options.tenant(), version.connectorId(), sourceId,
                                version.resourceId(), version.versionId())
                                .filter("GRAPH_COMMITTED"::equals)
                                .isPresent()) {
                    return version;
                }
            }
            for (SourceVersionRow version : ordered) {
                if (versionHasChunks.containsKey(version.versionId())) {
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
                hide("superseded", chunkId, sourceId, "supporting revision is not current");
            } else {
                hide("unmapped", chunkId, sourceId, "no resolvable current support");
            }
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
