package org.synanton.gateway.plan;

import org.synanton.gateway.domain.GraphEdge;
import org.synanton.gateway.domain.GraphEntity;
import org.synanton.gateway.domain.GraphResult;
import org.synanton.gateway.domain.Hit;
import org.synanton.gateway.domain.SourceRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FusionEngine {

    public List<Hit> fuse(List<Hit> hits, GraphResult graph, int topK, double graphPromotionBonus) {
        Set<String> graphRefs = collectGraphRefs(graph);

        List<Hit> promoted = new ArrayList<>();
        List<Hit> rest = new ArrayList<>();

        for (Hit h : hits) {
            if (h.contentRefId() != null && graphRefs.contains(h.contentRefId())) {
                promoted.add(h.withScore(h.score() + graphPromotionBonus).withGraphPromoted(true));
            } else {
                rest.add(h);
            }
        }

        promoted.sort(Comparator.comparingDouble(Hit::score).reversed());
        rest.sort(Comparator.comparingDouble(Hit::score).reversed());

        List<Hit> fused = new ArrayList<>(promoted);
        fused.addAll(rest);

        return fused.stream().limit(topK).toList();
    }

    private Set<String> collectGraphRefs(GraphResult graph) {
        Set<String> refs = new HashSet<>();
        if (graph == null) return refs;

        if (graph.entities() != null) {
            for (GraphEntity e : graph.entities()) {
                if (e.sourceRefs() != null) {
                    e.sourceRefs().stream()
                            .map(SourceRef::contentRefId)
                            .filter(id -> id != null)
                            .forEach(refs::add);
                }
            }
        }
        if (graph.edges() != null) {
            for (GraphEdge edge : graph.edges()) {
                if (edge.sourceRefs() != null) {
                    edge.sourceRefs().stream()
                            .map(SourceRef::contentRefId)
                            .filter(id -> id != null)
                            .forEach(refs::add);
                }
            }
        }
        return refs;
    }
}
