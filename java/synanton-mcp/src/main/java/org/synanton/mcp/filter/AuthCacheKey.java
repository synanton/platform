package org.synanton.mcp.filter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Cache key for a bearer token. The raw bearer must never sit in the cache as a key (heap-dump exposure) — entries are addressed by SHA-256 of the token instead. */
final class AuthCacheKey {
    private AuthCacheKey() {}

    static String of(String bearer) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(bearer.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // guaranteed by spec
        }
    }
}
