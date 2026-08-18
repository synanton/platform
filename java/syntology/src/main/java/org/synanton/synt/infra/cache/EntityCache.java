package org.synanton.synt.infra.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;
import org.synanton.synt.app.SyntologyProperties;
import org.synanton.synt.domain.model.EntityType;

import java.time.Duration;
import java.util.Optional;

@Component
public class EntityCache {

    private final Cache<String, EntityType> cache;

    public EntityCache(SyntologyProperties properties) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(properties.cache().entityTtlSeconds()))
                .maximumSize(10_000)
                .build();
    }

    public Optional<EntityType> get(String key) {
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    public void put(String key, EntityType entity) {
        cache.put(key, entity);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    public static String key(String tenant, String version, String label) {
        return tenant + ":" + version + ":" + label.toLowerCase();
    }
}
