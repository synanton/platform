package org.synanton.syntology.domain.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.synanton.syntology.domain.port.out.MetadataRepository;
import org.synanton.syntology.infra.jdbc.SessionPinRepository;
import org.synanton.syntology.infra.jdbc.SessionPinRepository.SessionPinRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class SessionPinService {

    private final SessionPinRepository sessionPinRepository;
    private final MetadataRepository metadataRepository;

    /** Short-lived read-through cache: key = "tenantId:subjectId", value = pinned version string. */
    private final Cache<String, String> pinCache;

    public SessionPinService(
            SessionPinRepository sessionPinRepository,
            MetadataRepository metadataRepository
    ) {
        this.sessionPinRepository = sessionPinRepository;
        this.metadataRepository = metadataRepository;
        this.pinCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(30))
                .maximumSize(10_000)
                .build();
    }

    /**
     * Returns the pinned version for the given subject if an active pin exists,
     * otherwise returns {@code fallbackVersion}.
     */
    public String resolveVersion(String tenantId, String subjectId, String fallbackVersion) {
        String cacheKey = cacheKey(tenantId, subjectId);
        String cached = pinCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        return sessionPinRepository.findActivePin(tenantId, subjectId)
                .map(v -> {
                    pinCache.put(cacheKey, v);
                    return v;
                })
                .orElse(fallbackVersion);
    }

    /**
     * Creates or updates a session pin. Validates that the requested version exists
     * before persisting the pin.
     *
     * @throws IllegalArgumentException if the version does not exist for the tenant.
     */
    public SessionPinRecord pin(String tenantId, String subjectId, String version, Duration ttl) {
        // Validate version exists
        metadataRepository.findByVersion(tenantId, version)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + version));

        Instant expiresAt = Instant.now().plus(ttl);
        sessionPinRepository.upsert(tenantId, subjectId, version, expiresAt);
        pinCache.invalidate(cacheKey(tenantId, subjectId));

        return sessionPinRepository.findPin(tenantId, subjectId)
                .orElseThrow(() -> new IllegalStateException("Pin was not persisted"));
    }

    /**
     * Removes the session pin for the given subject.
     *
     * @return true if a pin was found and deleted.
     */
    public boolean unpin(String tenantId, String subjectId) {
        pinCache.invalidate(cacheKey(tenantId, subjectId));
        return sessionPinRepository.delete(tenantId, subjectId);
    }

    /**
     * Returns the current pin record for the given subject, or empty if none exists.
     */
    public Optional<SessionPinRecord> getPin(String tenantId, String subjectId) {
        return sessionPinRepository.findPin(tenantId, subjectId);
    }

    private static String cacheKey(String tenantId, String subjectId) {
        return tenantId + ":" + subjectId;
    }
}
