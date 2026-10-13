package org.synanton.contentcache;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Opaque content reference (proposal §4.1.1, v1 format).
 *
 * <p>Wire format: {@code v1:tenant_id=...&artifact_id=...&digest=...&generation=...&serializer_version=...}
 * (URI-encoded). The format is composed by Content Cache, never by callers;
 * this type only parses and re-encodes values the service returned.
 * Maximum encoded length is 512 characters.
 */
public record ContentReference(
        String tenantId,
        String artifactId,
        String digest,
        int generation,
        int serializerVersion) {

    public static final int MAX_LENGTH = 512;

    public ContentReference {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(digest, "digest");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (artifactId.isBlank()) {
            throw new IllegalArgumentException("artifactId must not be blank");
        }
        if (digest.isBlank()) {
            throw new IllegalArgumentException("digest must not be blank");
        }
    }

    public String encode() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("tenant_id", tenantId);
        params.put("artifact_id", artifactId);
        params.put("digest", digest);
        params.put("generation", Integer.toString(generation));
        params.put("serializer_version", Integer.toString(serializerVersion));
        StringBuilder sb = new StringBuilder("v1:");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        String encoded = sb.toString();
        if (encoded.length() > MAX_LENGTH) {
            throw new IllegalStateException("encoded reference exceeds 512 characters");
        }
        return encoded;
    }

    public static ContentReference parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        if (!raw.startsWith("v1:")) {
            throw new IllegalArgumentException("unsupported reference format: expected v1 prefix");
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : raw.substring(3).split("&", -1)) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("malformed reference pair: " + pair);
            }
            params.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
        }
        String tenantId = params.get("tenant_id");
        String artifactId = params.get("artifact_id");
        String digest = params.get("digest");
        if (tenantId == null || tenantId.isBlank()
                || artifactId == null || artifactId.isBlank()
                || digest == null || digest.isBlank()) {
            throw new IllegalArgumentException("reference is missing tenant_id, artifact_id, or digest");
        }
        return new ContentReference(
                tenantId,
                artifactId,
                digest,
                parseIntOr(params.get("generation"), 1),
                parseIntOr(params.get("serializer_version"), 1));
    }

    private static int parseIntOr(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
