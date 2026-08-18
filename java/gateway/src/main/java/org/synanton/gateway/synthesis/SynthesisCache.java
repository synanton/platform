package org.synanton.gateway.synthesis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SynthesisCache {

    public record CacheEntry(String answer, String aclMask) {}

    private final ConcurrentHashMap<String, CacheEntry> store = new ConcurrentHashMap<>();

    public String key(String query, String ontologyVersion, String modelId, String aclMask) {
        String raw = query.strip().toLowerCase() + "|" + ontologyVersion + "|" + modelId + "|" + aclMask;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    public CacheEntry get(String key, String callerMask) {
        CacheEntry entry = store.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.aclMask().contains(callerMask) || callerMask.contains(entry.aclMask())) {
            return entry;
        }
        return null;
    }

    public void put(String key, String answer, String aclMask) {
        store.put(key, new CacheEntry(answer, aclMask));
    }

    public Map<String, CacheEntry> snapshot() {
        return Map.copyOf(store);
    }
}
