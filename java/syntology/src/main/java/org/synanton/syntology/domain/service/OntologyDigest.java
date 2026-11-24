package org.synanton.syntology.domain.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/** Shared SHA-256 helper for ontology release identity. */
public final class OntologyDigest {
    private OntologyDigest() {
    }

    /**
     * Canonical ontology release digest. Component bytes are hashed with
     * SHA-256 and named in a sorted v1 manifest; releaseDigest is the SHA-256
     * of that manifest. Component hashes are inputs, not separate API fields.
     */
    public static String releaseDigest(String format, Map<String, byte[]> components) {
        if (format == null || format.isBlank()) {
            throw new IllegalArgumentException("format is required");
        }
        StringBuilder manifest = new StringBuilder("ontology-release-v1\n")
                .append("format=").append(format).append('\n');
        for (Map.Entry<String, byte[]> entry : new TreeMap<>(components).entrySet()) {
            manifest.append("component.").append(entry.getKey())
                    .append("=sha256:").append(sha256(entry.getValue())).append('\n');
        }
        return sha256(manifest.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                digest.update(part);
            }
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest.digest()) {
                out.append(String.format("%02x", value));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
