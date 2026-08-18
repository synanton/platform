package org.synanton.gateway.plan;

import org.junit.jupiter.api.Test;
import org.synanton.gateway.domain.GraphEdge;
import org.synanton.gateway.domain.GraphEntity;
import org.synanton.gateway.domain.GraphResult;
import org.synanton.gateway.domain.Hit;
import org.synanton.gateway.domain.SourceRef;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FusionEngineTest {

    private final FusionEngine engine = new FusionEngine();

    private static Hit hit(String id, double score) {
        return new Hit(id, 0, score, score, 0.0, false, "snippet", "file:///" + id);
    }

    @Test
    void promotesHitsFoundInGraphEntities() {
        List<Hit> hits = List.of(hit("doc1", 0.5), hit("doc2", 0.6));
        GraphEntity entity = new GraphEntity("e1", "Acme", "ORG", List.of(new SourceRef("doc1")));
        GraphResult graph = new GraphResult(List.of(entity), List.of(), List.of());

        List<Hit> fused = engine.fuse(hits, graph, 10, 0.1);

        // doc1 gets promoted bonus 0.1: score 0.6; doc2 stays at 0.6 - promoted comes first
        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).contentRefId()).isEqualTo("doc1");
        assertThat(fused.get(0).graphPromoted()).isTrue();
        assertThat(fused.get(0).score()).isEqualTo(0.6, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void promotesHitsFoundInGraphEdges() {
        List<Hit> hits = List.of(hit("doc3", 0.4));
        GraphEdge edge = new GraphEdge("e1", "e2", "SUPPLIES", List.of(new SourceRef("doc3")));
        GraphResult graph = new GraphResult(List.of(), List.of(edge), List.of());

        List<Hit> fused = engine.fuse(hits, graph, 10, 0.1);

        assertThat(fused.get(0).graphPromoted()).isTrue();
        assertThat(fused.get(0).score()).isEqualTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void respectsTopKLimit() {
        List<Hit> hits = List.of(
                hit("a", 0.9), hit("b", 0.8), hit("c", 0.7),
                hit("d", 0.6), hit("e", 0.5)
        );

        List<Hit> fused = engine.fuse(hits, GraphResult.empty(), 3, 0.1);

        assertThat(fused).hasSize(3);
        assertThat(fused.get(0).contentRefId()).isEqualTo("a");
    }

    @Test
    void emptyGraphDoesNotPromoteAnything() {
        List<Hit> hits = List.of(hit("x", 0.7), hit("y", 0.5));

        List<Hit> fused = engine.fuse(hits, GraphResult.empty(), 10, 0.1);

        assertThat(fused).allMatch(h -> !h.graphPromoted());
        assertThat(fused.get(0).contentRefId()).isEqualTo("x");
    }

    @Test
    void promotedHitsSortedBeforeRest() {
        List<Hit> hits = List.of(
                hit("low", 0.3),
                hit("high", 0.7),
                hit("promoted-low", 0.2)
        );
        GraphEntity e = new GraphEntity("e", "E", "T", List.of(new SourceRef("promoted-low")));
        GraphResult graph = new GraphResult(List.of(e), List.of(), List.of());

        List<Hit> fused = engine.fuse(hits, graph, 10, 0.1);

        // promoted-low gets 0.3 after bonus → still behind high(0.7) and low(0.3)
        // promoted partition: [promoted-low(0.3)]
        // rest partition: [high(0.7), low(0.3)]
        // result: promoted first → [promoted-low, high, low]
        assertThat(fused.get(0).contentRefId()).isEqualTo("promoted-low");
        assertThat(fused.get(0).graphPromoted()).isTrue();
    }
}
