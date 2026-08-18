package org.synanton.security.idp;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.synanton.common.error.AuthException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ApiKeyIdentityProvider implements IdentityProviderPort {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyIdentityProvider.class);
    private static final String PREFIX = "syn_";

    private final JdbcTemplate jdbc;
    private final Cache<String, ValidatedIdentity> cache;

    public ApiKeyIdentityProvider(JdbcTemplate jdbc, long cacheTtlSeconds, long cacheMaxSize) {
        this.jdbc = jdbc;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(cacheTtlSeconds, TimeUnit.SECONDS)
                .maximumSize(cacheMaxSize)
                .build();
    }

    @Override
    public boolean supports(String authorizationHeader) {
        return authorizationHeader != null
                && authorizationHeader.startsWith("Bearer " + PREFIX);
    }

    @Override
    public ValidatedIdentity resolve(String authorizationHeader) throws AuthException {
        String rawKey = authorizationHeader.substring("Bearer ".length());
        String lookupHash = sha256Hex(rawKey);

        ValidatedIdentity cached = cache.getIfPresent(lookupHash);
        if (cached != null) return cached;

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT key_id, tenant_id, subject_id, key_hash, scopes FROM security.api_keys " +
                "WHERE key_lookup_hash = ? AND revoked_at IS NULL " +
                "AND (expires_at IS NULL OR expires_at > now())",
                lookupHash
        );

        if (rows.isEmpty()) throw new AuthException("Invalid API key");

        Map<String, Object> row = rows.get(0);
        String storedHash = (String) row.get("key_hash");

        BCrypt.Result result = BCrypt.verifyer().verify(rawKey.toCharArray(), storedHash.toCharArray());
        if (!result.verified) throw new AuthException("Invalid API key");

        String keyId = row.get("key_id").toString();
        String tenantId = (String) row.get("tenant_id");
        String subjectId = (String) row.get("subject_id");

        String[] scopes = new String[0];
        Object scopesObj = row.get("scopes");
        if (scopesObj instanceof java.sql.Array arr) {
            try {
                scopes = (String[]) arr.getArray();
            } catch (Exception e) {
                log.warn("Failed to read scopes array for key {}", keyId, e);
            }
        }

        ValidatedIdentity identity = new ValidatedIdentity(subjectId, tenantId, "API_KEY", scopes, keyId);
        cache.put(lookupHash, identity);
        return identity;
    }

    public void invalidate(String rawKey) {
        cache.invalidate(sha256Hex(rawKey));
    }

    public void invalidateByLookupHash(String lookupHash) {
        cache.invalidate(lookupHash);
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
