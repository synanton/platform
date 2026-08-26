package org.synanton.relix.graph;

import org.junit.jupiter.api.Test;
import org.synanton.relix.api.dto.SourceRef;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SourceRefCodecTest {

    @Test
    void shouldRoundTripSourceRefs() {
        UUID first = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID second = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        Map<UUID, Set<Integer>> refs = new LinkedHashMap<>();
        refs.put(first, new LinkedHashSet<>(List.of(1, 2)));
        refs.put(second, new LinkedHashSet<>(List.of(3)));

        String encoded = SourceRefCodec.encode(refs);
        List<SourceRef> decoded = SourceRefCodec.decode(encoded);

        assertThat(decoded).containsExactly(
                new SourceRef(first, List.of(1, 2)),
                new SourceRef(second, List.of(3)));
    }
}
