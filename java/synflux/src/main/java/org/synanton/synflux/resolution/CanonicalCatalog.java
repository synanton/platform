package org.synanton.synflux.resolution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Immutable canonical concept catalog with a deterministic digest. */
public record CanonicalCatalog(String version, List<CanonicalConcept> concepts) {

    public CanonicalCatalog {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        concepts = List.copyOf(concepts);
        Set<String> ids = new HashSet<>();
        for (CanonicalConcept concept : concepts) {
            if (!ids.add(concept.canonicalId())) {
                throw new IllegalArgumentException("duplicate canonical id: " + concept.canonicalId());
            }
        }
    }

    public String digest() {
        List<CanonicalConcept> sorted = new ArrayList<>(concepts);
        sorted.sort(Comparator.comparing(CanonicalConcept::canonicalId));
        StringBuilder canonical = new StringBuilder("canonical-catalog-v1\n");
        canonical.append("version=").append(version).append('\n');
        for (CanonicalConcept concept : sorted) {
            List<String> aliases = new ArrayList<>(concept.aliases());
            aliases.sort(String::compareTo);
            canonical.append("concept=").append(concept.canonicalId()).append('|')
                .append(ResolutionText.normalize(concept.label())).append('|')
                .append(concept.type()).append('|')
                .append(String.join(",", aliases.stream().map(ResolutionText::normalize).toList()))
                .append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
