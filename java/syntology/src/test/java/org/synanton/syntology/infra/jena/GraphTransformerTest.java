package org.synanton.syntology.infra.jena;

import org.junit.jupiter.api.Test;
import org.synanton.syntology.domain.model.OntologyGraph;

import static org.assertj.core.api.Assertions.assertThat;

class GraphTransformerTest {

    @Test
    void shouldProduceNodesFromTurtleModel() throws Exception {
        var adapter = new JenaTdb2Adapter();
        var tmp = java.nio.file.Files.createTempDirectory("syntology-unit");
        adapter.init(tmp.toString());
        byte[] ttl = """
                @prefix ex: <http://example.org#> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                ex:A a owl:Class ; rdfs:label "A" .
                ex:B a owl:Class ; rdfs:label "B" ; rdfs:subClassOf ex:A .
                """.getBytes();
        adapter.persistTurtle("demo", "1.0.0", ttl);
        OntologyGraph graph = adapter.loadOntology("demo", "1.0.0");
        assertThat(graph.nodes()).isNotEmpty();
        assertThat(graph.edges()).isNotEmpty();
    }
}
