package org.synanton.syntology.domain.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Shared SHA-256 helper for ontology release identity. */
public final class OntologyDigest {
    private OntologyDigest() {
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
