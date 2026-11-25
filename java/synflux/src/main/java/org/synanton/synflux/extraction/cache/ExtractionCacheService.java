package org.synanton.synflux.extraction.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.synanton.extraction.engine.ExtractionIntegrity;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.LlmProvenance;
import org.synanton.extraction.engine.LlmUsage;
import org.synanton.extraction.engine.PreEntity;
import org.synanton.extraction.engine.RelationCandidate;
import org.synanton.extraction.engine.provenance.ExtractionCacheKey;
import org.synanton.ingestioncache.cache.ExtractionCacheEntry;
import org.synanton.ingestioncache.cache.ExtractionCacheStore;

/**
 * #829 cache-hit boundary. A hit returns the original generation and candidates
 * without invoking the loader (and therefore without a model call or generation
 * creation). A miss delegates once, then persists the result against the caller's
 * generation. This service never promotes or moves the ACTIVE pointer.
 */
public final class ExtractionCacheService {

    public record CachedExtractionResult(ExtractionResult result, UUID generationId,
                                         boolean cacheHit) {
        public CachedExtractionResult {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(generationId, "generationId");
        }
    }

    private static final TypeReference<List<PreEntity>> ENTITIES = new TypeReference<>() {};
    private static final TypeReference<List<RelationCandidate>> RELATIONS = new TypeReference<>() {};

    private final ExtractionCacheStore store;
    private final ObjectMapper mapper;

    public ExtractionCacheService(ExtractionCacheStore store, ObjectMapper mapper) {
        this.store = Objects.requireNonNull(store, "store");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public Optional<CachedExtractionResult> lookup(ExtractionCacheKey key) {
        Objects.requireNonNull(key, "key");
        return store.get(key.tenantId(), key.sourceVersionId(), key.cacheKey())
                .map(entry -> new CachedExtractionResult(toResult(entry), entry.generationId(), true));
    }

    public void put(ExtractionCacheKey key, UUID generationId, ExtractionResult result) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(generationId, "generationId");
        Objects.requireNonNull(result, "result");
        store.put(new ExtractionCacheEntry(key.tenantId(), key.sourceVersionId(), key.cacheKey(),
                generationId, write(result.preEntities()), write(result.relations()),
                result.provenance().promptVersion(), result.provenance().promptCompiledDigest(),
                result.provenance().promptRenderedDigest(), result.provenance().schemaVersion(),
                result.provenance().schemaDigest(), Instant.now(),
                result.provenance().finishReason(), result.provenance().gatewayRequestId(),
                result.provenance().gatewayExecutionId()));
    }

    public CachedExtractionResult getOrLoad(ExtractionCacheKey key, UUID generationId,
                                            Supplier<ExtractionResult> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        Optional<CachedExtractionResult> cached = lookup(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        Objects.requireNonNull(generationId, "generationId");
        ExtractionResult result = Objects.requireNonNull(loader.get(), "loader result");
        put(key, generationId, result);
        return new CachedExtractionResult(result, generationId, false);
    }

    private ExtractionResult toResult(ExtractionCacheEntry entry) {
        LlmProvenance provenance = new LlmProvenance(entry.promptVersion(),
                entry.promptCompiledDigest(), entry.promptRenderedDigest(),
                entry.schemaVersion(), entry.schemaDigest(), entry.finishReason(),
                entry.gatewayRequestId(), entry.gatewayExecutionId());
        return new ExtractionResult(read(entry.entitiesJson(), ENTITIES),
                read(entry.relationsJson(), RELATIONS), LlmUsage.NONE, List.of(),
                ExtractionIntegrity.CLEAN, provenance);
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize cached extraction", e);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize cached extraction", e);
        }
    }
}
