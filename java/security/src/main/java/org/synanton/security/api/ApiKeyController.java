package org.synanton.security.api;

import at.favre.lib.crypto.bcrypt.BCrypt;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.synanton.security.idp.ApiKeyIdentityProvider;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/auth/api-keys")
public class ApiKeyController {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int KEY_LENGTH = 48;
    private static final String PREFIX = "syn_";

    private final JdbcTemplate jdbc;

    public ApiKeyController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping
    public ResponseEntity<ApiKeyResponse> generate(@RequestBody GenerateRequest req) {
        String rawKey = generateKey();
        String keyHash = BCrypt.withDefaults().hashToString(12, rawKey.toCharArray());
        String lookupHash = ApiKeyIdentityProvider.sha256Hex(rawKey);
        String tenantId = "demo";
        String subjectId = "user:admin";

        String[] scopes = req.scopes() != null ? req.scopes().toArray(new String[0]) : new String[0];

        UUID keyId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.execute((java.sql.Connection conn) -> {
            try (var ps = conn.prepareStatement(
                    "INSERT INTO security.api_keys (key_id, tenant_id, subject_id, key_hash, key_lookup_hash, label, scopes, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setObject(1, keyId);
                ps.setString(2, tenantId);
                ps.setString(3, subjectId);
                ps.setString(4, keyHash);
                ps.setString(5, lookupHash);
                ps.setString(6, req.label());
                ps.setArray(7, conn.createArrayOf("text", scopes));
                ps.setObject(8, now);
                ps.executeUpdate();
            }
            return null;
        });

        return ResponseEntity.created(URI.create("/auth/api-keys/" + keyId))
                .body(new ApiKeyResponse(keyId.toString(), rawKey, req.label(), List.of(scopes), now.toString()));
    }

    @GetMapping
    public ResponseEntity<List<ApiKeyListItem>> list(
            @RequestParam(defaultValue = "20") int limit) {
        List<ApiKeyListItem> keys = jdbc.query(
                "SELECT key_id, tenant_id, subject_id, label, scopes, created_at FROM security.api_keys " +
                "WHERE tenant_id = 'demo' AND revoked_at IS NULL ORDER BY created_at DESC LIMIT ?",
                (rs, i) -> {
                    String[] scopes = new String[0];
                    try {
                        java.sql.Array arr = rs.getArray("scopes");
                        if (arr != null) scopes = (String[]) arr.getArray();
                    } catch (Exception ignored) {}
                    return new ApiKeyListItem(
                            rs.getString("key_id"),
                            rs.getString("tenant_id"),
                            rs.getString("subject_id"),
                            rs.getString("label"),
                            List.of(scopes),
                            rs.getString("created_at")
                    );
                },
                limit
        );
        return ResponseEntity.ok(keys);
    }

    @DeleteMapping("/{keyId}")
    public ResponseEntity<Void> revoke(@PathVariable String keyId) {
        int updated = jdbc.update(
                "UPDATE security.api_keys SET revoked_at = now() WHERE key_id = ? AND revoked_at IS NULL",
                UUID.fromString(keyId)
        );
        return updated > 0 ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/{keyId}/rotate")
    public ResponseEntity<ApiKeyResponse> rotate(@PathVariable String keyId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT tenant_id, subject_id, label, scopes FROM security.api_keys WHERE key_id = ? AND revoked_at IS NULL",
                UUID.fromString(keyId)
        );
        if (rows.isEmpty()) return ResponseEntity.notFound().build();

        Map<String, Object> old = rows.get(0);
        String tenantId = (String) old.get("tenant_id");
        String subjectId = (String) old.get("subject_id");
        String label = (String) old.get("label");
        String[] scopes = new String[0];
        try {
            java.sql.Array arr = (java.sql.Array) old.get("scopes");
            if (arr != null) scopes = (String[]) arr.getArray();
        } catch (Exception ignored) {}

        String rawKey = generateKey();
        String keyHash = BCrypt.withDefaults().hashToString(12, rawKey.toCharArray());
        String lookupHash = ApiKeyIdentityProvider.sha256Hex(rawKey);
        UUID newKeyId = UUID.randomUUID();
        Instant now = Instant.now();
        String[] finalScopes = scopes;

        jdbc.execute((java.sql.Connection conn) -> {
            try (var ps1 = conn.prepareStatement(
                    "UPDATE security.api_keys SET revoked_at = now() WHERE key_id = ?");
                 var ps2 = conn.prepareStatement(
                    "INSERT INTO security.api_keys (key_id, tenant_id, subject_id, key_hash, key_lookup_hash, label, scopes, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps1.setObject(1, UUID.fromString(keyId));
                ps1.executeUpdate();
                ps2.setObject(1, newKeyId);
                ps2.setString(2, tenantId);
                ps2.setString(3, subjectId);
                ps2.setString(4, keyHash);
                ps2.setString(5, lookupHash);
                ps2.setString(6, label);
                ps2.setArray(7, conn.createArrayOf("text", finalScopes));
                ps2.setObject(8, now);
                ps2.executeUpdate();
            }
            return null;
        });

        return ResponseEntity.ok(new ApiKeyResponse(newKeyId.toString(), rawKey, label, List.of(scopes), now.toString()));
    }

    private static String generateKey() {
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < KEY_LENGTH; i++) {
            sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public record GenerateRequest(String label, List<String> scopes) {}
    public record ApiKeyResponse(String keyId, String key, String label, List<String> scopes, String createdAt) {}
    public record ApiKeyListItem(String keyId, String tenantId, String subjectId, String label, List<String> scopes, String createdAt) {}
}
