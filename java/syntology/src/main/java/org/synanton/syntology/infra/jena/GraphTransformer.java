package org.synanton.syntology.infra.jena;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.synanton.syntology.domain.model.OntologyGraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GraphTransformer {

    private GraphTransformer() {
    }

    public static OntologyGraph fromModel(Model model) {
        List<OntologyGraph.GraphNode> nodes = new ArrayList<>();
        List<OntologyGraph.GraphEdge> edges = new ArrayList<>();
        Map<String, OntologyGraph.GraphNode> nodeById = new HashMap<>();
        Set<String> edgeKeys = new HashSet<>();

        Resource owlClass = model.createResource("http://www.w3.org/2002/07/owl#Class");
        model.listSubjectsWithProperty(RDF.type, RDFS.Class).forEachRemaining(resource ->
                addNode(nodes, nodeById, resource, "Class"));
        model.listSubjectsWithProperty(RDF.type, owlClass).forEachRemaining(resource ->
                addNode(nodes, nodeById, resource, "Class"));
        model.listSubjectsWithProperty(RDF.type, RDF.Property).forEachRemaining(resource ->
                addNode(nodes, nodeById, resource, "Property"));

        model.listStatements(null, RDFS.subClassOf, (RDFNode) null).forEachRemaining(stmt ->
                addEdge(edges, edgeKeys, nodeById, nodes, stmt.getSubject(), stmt.getObject(), "subClassOf"));
        model.listStatements(null, RDFS.domain, (RDFNode) null).forEachRemaining(stmt ->
                addEdge(edges, edgeKeys, nodeById, nodes, stmt.getSubject(), stmt.getObject(), "domain"));
        model.listStatements(null, RDFS.range, (RDFNode) null).forEachRemaining(stmt ->
                addEdge(edges, edgeKeys, nodeById, nodes, stmt.getSubject(), stmt.getObject(), "range"));

        return new OntologyGraph(List.copyOf(nodes), List.copyOf(edges));
    }

    private static void addNode(
            List<OntologyGraph.GraphNode> nodes,
            Map<String, OntologyGraph.GraphNode> nodeById,
            Resource resource,
            String type
    ) {
        String id = resource.getURI();
        if (id == null || nodeById.containsKey(id)) {
            return;
        }
        String label = resource.getProperty(RDFS.label) != null
                ? resource.getProperty(RDFS.label).getString()
                : localName(id);
        OntologyGraph.GraphNode node = new OntologyGraph.GraphNode(id, label, type);
        nodeById.put(id, node);
        nodes.add(node);
    }

    private static void addEdge(
            List<OntologyGraph.GraphEdge> edges,
            Set<String> edgeKeys,
            Map<String, OntologyGraph.GraphNode> nodeById,
            List<OntologyGraph.GraphNode> nodes,
            Resource source,
            RDFNode targetNode,
            String label
    ) {
        if (!targetNode.isResource()) {
            return;
        }
        Resource target = targetNode.asResource();
        String sourceId = source.getURI();
        String targetId = target.getURI();
        if (sourceId == null || targetId == null) {
            return;
        }
        ensureNode(nodes, nodeById, source, inferType(source));
        ensureNode(nodes, nodeById, target, inferType(target));
        String key = sourceId + "|" + targetId + "|" + label;
        if (edgeKeys.add(key)) {
            edges.add(new OntologyGraph.GraphEdge(sourceId, targetId, label));
        }
    }

    private static void ensureNode(
            List<OntologyGraph.GraphNode> nodes,
            Map<String, OntologyGraph.GraphNode> nodeById,
            Resource resource,
            String type
    ) {
        String id = resource.getURI();
        if (id != null && !nodeById.containsKey(id)) {
            String label = resource.getProperty(RDFS.label) != null
                    ? resource.getProperty(RDFS.label).getString()
                    : localName(id);
            OntologyGraph.GraphNode node = new OntologyGraph.GraphNode(id, label, type);
            nodeById.put(id, node);
            nodes.add(node);
        }
    }

    private static String inferType(Resource resource) {
        if (resource.hasProperty(RDF.type, RDF.Property)) {
            return "Property";
        }
        return "Class";
    }

    private static String localName(String uri) {
        int hash = uri.lastIndexOf('#');
        if (hash >= 0) {
            return uri.substring(hash + 1);
        }
        int slash = uri.lastIndexOf('/');
        return slash >= 0 ? uri.substring(slash + 1) : uri;
    }
}
