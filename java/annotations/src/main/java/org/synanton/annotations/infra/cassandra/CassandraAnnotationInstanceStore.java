package org.synanton.annotations.infra.cassandra;

import org.springframework.stereotype.Component;
import org.synanton.annotations.domain.resolutor.AnnotationInstanceStore;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.AnnotationRow;

import java.time.Instant;
import java.util.List;

@Component
public class CassandraAnnotationInstanceStore implements AnnotationInstanceStore {

    private final IngestionCacheClient cacheClient;

    public CassandraAnnotationInstanceStore(IngestionCacheClient cacheClient) {
        this.cacheClient = cacheClient;
    }

    @Override
    public List<TargetRef> findTargets(String tenantId, String definitionId) {
        return cacheClient.readAnnotationsByDefinition(tenantId, definitionId).stream()
                .filter(row -> row.invalidatedAt() == null)
                .map(row -> new TargetRef(row.targetType(), row.targetId()))
                .distinct()
                .toList();
    }

    @Override
    public void invalidate(String tenantId, String targetType, String targetId, String definitionId, int version) {
        Instant now = Instant.now();
        cacheClient.readAnnotations(tenantId, targetType, targetId).stream()
                .filter(row -> matchesLiveInstance(row, definitionId, version))
                .forEach(row -> cacheClient.invalidateAnnotation(tenantId, targetType, targetId, row.annotationId(), now));
    }

    private static boolean matchesLiveInstance(AnnotationRow row, String definitionId, int version) {
        return definitionId.equals(row.definitionId()) && row.definitionVersion() == version && row.invalidatedAt() == null;
    }
}
