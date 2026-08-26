package org.synanton.relix.graph;

import org.synanton.relix.api.dto.SourceRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SourceRefCodec {

    private SourceRefCodec() {}

    public static String encode(Map<UUID, Set<Integer>> refs) {
        if (refs == null || refs.isEmpty()) {
            return "";
        }
        StringBuilder encoded = new StringBuilder();
        refs.forEach((contentRefId, ordinals) -> {
            if (encoded.length() > 0) {
                encoded.append('|');
            }
            encoded.append(contentRefId).append(':');
            boolean first = true;
            for (Integer ordinal : ordinals) {
                if (!first) {
                    encoded.append(',');
                }
                encoded.append(ordinal);
                first = false;
            }
        });
        return encoded.toString();
    }

    public static List<SourceRef> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<SourceRef> refs = new ArrayList<>();
        for (String part : encoded.split("\\|")) {
            int colon = part.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            UUID contentRefId = UUID.fromString(part.substring(0, colon));
            List<Integer> ordinals = new ArrayList<>();
            String rest = part.substring(colon + 1);
            if (!rest.isBlank()) {
                for (String token : rest.split(",")) {
                    ordinals.add(Integer.parseInt(token));
                }
            }
            refs.add(new SourceRef(contentRefId, ordinals));
        }
        return refs;
    }
}
