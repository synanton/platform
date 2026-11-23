package org.synanton.ingestioncache.cache;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable cache identity store (#829). The cache row points at the original
 * generation; deleting/invalidating the cache row never deletes the generation
 * or its provenance.
 */
public final class ExtractionCacheStore {

    private final CqlSession session;

    public ExtractionCacheStore(CqlSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    public Optional<ExtractionCacheEntry> get(String tenantId, UUID sourceVersionId, String cacheKey) {
        requireKey(tenantId, sourceVersionId, cacheKey);
        Row row = session.execute(SimpleStatement.newInstance("""
                SELECT * FROM ingestion_cache.extraction_cache_entries
                WHERE tenant_id=? AND source_version_id=? AND cache_key=?
                """, tenantId, sourceVersionId, cacheKey)).one();
        return row == null ? Optional.empty() : Optional.of(entry(row));
    }

    public void put(ExtractionCacheEntry entry) {
        Objects.requireNonNull(entry, "entry");
        requireKey(entry.tenantId(), entry.sourceVersionId(), entry.cacheKey());
        session.execute(SimpleStatement.newInstance("""
                INSERT INTO ingestion_cache.extraction_cache_entries
                    (tenant_id, source_version_id, cache_key, generation_id, entities_json,
                     relations_json, prompt_version, prompt_compiled_digest,
                     prompt_rendered_digest, schema_version, schema_digest, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, entry.tenantId(), entry.sourceVersionId(), entry.cacheKey(),
                entry.generationId(), entry.entitiesJson(), entry.relationsJson(),
                entry.promptVersion(), entry.promptCompiledDigest(), entry.promptRenderedDigest(),
                entry.schemaVersion(), entry.schemaDigest(), entry.createdAt()));
    }

    public void invalidate(String tenantId, UUID sourceVersionId, String cacheKey) {
        requireKey(tenantId, sourceVersionId, cacheKey);
        session.execute(SimpleStatement.newInstance("""
                DELETE FROM ingestion_cache.extraction_cache_entries
                WHERE tenant_id=? AND source_version_id=? AND cache_key=?
                """, tenantId, sourceVersionId, cacheKey));
    }

    public void deleteForSource(String tenantId, UUID sourceVersionId) {
        requireKey(tenantId, sourceVersionId, "source");
        session.execute(SimpleStatement.newInstance("""
                DELETE FROM ingestion_cache.extraction_cache_entries
                WHERE tenant_id=? AND source_version_id=?
                """, tenantId, sourceVersionId));
    }

    private static ExtractionCacheEntry entry(Row row) {
        return new ExtractionCacheEntry(
                row.getString("tenant_id"), row.getUuid("source_version_id"),
                row.getString("cache_key"), row.getUuid("generation_id"),
                row.getString("entities_json"), row.getString("relations_json"),
                row.getString("prompt_version"), row.getString("prompt_compiled_digest"),
                row.getString("prompt_rendered_digest"), row.getString("schema_version"),
                row.getString("schema_digest"), row.getInstant("created_at"));
    }

    private static void requireKey(String tenantId, UUID sourceVersionId, String cacheKey) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        Objects.requireNonNull(sourceVersionId, "sourceVersionId");
        if (cacheKey == null || cacheKey.isBlank()) {
            throw new IllegalArgumentException("cacheKey is required");
        }
    }
}
