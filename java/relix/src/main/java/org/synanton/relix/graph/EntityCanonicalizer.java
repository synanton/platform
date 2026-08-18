package org.synanton.relix.graph;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class EntityCanonicalizer {

    private static final UUID NS_ENT = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
    private static final UUID NS_EDGE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

    public UUID entityId(String tenant, String type, String label) {
        String key = tenant + "|" + type + "|" + normalise(label);
        return uuidV5(NS_ENT, key);
    }

    public UUID edgeId(String tenant, UUID fromId, String verb, UUID toId) {
        String key = tenant + "|" + fromId + "|" + verb + "|" + toId;
        return uuidV5(NS_EDGE, key);
    }

    public String normalise(String label) {
        if (label == null) return "";
        return label.toLowerCase().trim().replaceAll("\\s+", " ").replaceAll("[.!?]+$", "").trim();
    }

    static UUID uuidV5(UUID namespace, String name) {
        byte[] nsBytes = toBytes(namespace);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[nsBytes.length + nameBytes.length];
        System.arraycopy(nsBytes, 0, all, 0, nsBytes.length);
        System.arraycopy(nameBytes, 0, all, nsBytes.length, nameBytes.length);

        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(all);
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50); // version 5
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80); // variant
            return fromBytes(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 unavailable", e);
        }
    }

    private static byte[] toBytes(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        byte[] b = new byte[16];
        for (int i = 7; i >= 0; i--) { b[i] = (byte) msb; msb >>= 8; }
        for (int i = 15; i >= 8; i--) { b[i] = (byte) lsb; lsb >>= 8; }
        return b;
    }

    private static UUID fromBytes(byte[] b) {
        long msb = 0, lsb = 0;
        for (int i = 0; i < 8; i++) msb = (msb << 8) | (b[i] & 0xff);
        for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (b[i] & 0xff);
        return new UUID(msb, lsb);
    }
}
