package org.synanton.synt.infra.jena;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.synanton.synt.domain.model.EntityType;
import org.synanton.synt.domain.model.Feature;
import org.synanton.synt.domain.model.OntologyGraph;
import org.synanton.synt.domain.model.RelationType;
import org.synanton.synt.domain.port.out.OntologyAdapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class JenaTdb2Adapter implements OntologyAdapter {

    private Path storageRoot;

    @Override
    public void init(String storagePath) {
        this.storageRoot = Path.of(storagePath);
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create TDB2 storage root: " + storagePath, e);
        }
    }

    @Override
    public OntologyGraph loadOntology(String tenant, String version) {
        Model model = openModel(tenant, version);
        try {
            return GraphTransformer.fromModel(model);
        } finally {
            model.close();
        }
    }

    @Override
    public void persistOntology(String tenant, String version, OntologyGraph graph) {
        throw new UnsupportedOperationException("Use persistTurtle for demo persistence");
    }

    @Override
    public void persistTurtle(String tenant, String version, byte[] turtleBytes) {
        Path datasetDir = datasetPath(tenant, version);
        try {
            Files.createDirectories(datasetDir);
            Path ttlFile = datasetDir.resolve("ontology.ttl");
            Files.write(ttlFile, turtleBytes);
            Model model = ModelFactory.createDefaultModel();
            RDFDataMgr.read(model, new ByteArrayInputStream(turtleBytes), Lang.TURTLE);
            model.close();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist ontology for " + tenant + "/" + version, e);
        }
    }

    @Override
    public void persistShapes(String tenant, String version, byte[] turtleBytes) {
        writeVersionFile(tenant, version, "shapes.ttl", turtleBytes);
    }

    @Override
    public void persistSchemaIr(String tenant, String version, byte[] jsonBytes) {
        writeVersionFile(tenant, version, "schema.json", jsonBytes);
    }

    @Override
    public Optional<byte[]> loadShapes(String tenant, String version) {
        return readVersionFile(tenant, version, "shapes.ttl");
    }

    @Override
    public Optional<byte[]> loadSchemaIr(String tenant, String version) {
        return readVersionFile(tenant, version, "schema.json");
    }

    @Override
    public boolean versionExists(String tenant, String version) {
        return Files.exists(datasetPath(tenant, version).resolve("ontology.ttl"));
    }

    @Override
    public void deleteVersion(String tenant, String version) {
        Path datasetDir = datasetPath(tenant, version);
        if (!Files.exists(datasetDir)) {
            return;
        }
        try (var paths = Files.walk(datasetDir)) {
            paths.sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new IllegalStateException("Failed to delete " + path, e);
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete version " + version, e);
        }
    }

    @Override
    public boolean supportsFeature(Feature feature) {
        return feature == Feature.BASIC_GRAPH_STORAGE
                || feature == Feature.VERSIONING
                || feature == Feature.SHACL_VALIDATION;
    }

    public List<EntityType> listEntities(String tenant, String version) {
        Model model = openModel(tenant, version);
        try {
            List<EntityType> entities = new ArrayList<>();
            String sparql = """
                    PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                    PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                    SELECT ?uri ?label ?super WHERE {
                      ?uri a ?type .
                      FILTER(?type = rdfs:Class || ?type = <http://www.w3.org/2002/07/owl#Class>)
                      OPTIONAL { ?uri rdfs:label ?label }
                      OPTIONAL { ?uri rdfs:subClassOf ?super }
                    }
                    """;
            Map<String, EntityTypeBuilder> builders = new HashMap<>();
            try (QueryExecution qexec = QueryExecutionFactory.create(QueryFactory.create(sparql), model)) {
                ResultSet rs = qexec.execSelect();
                while (rs.hasNext()) {
                    QuerySolution row = rs.nextSolution();
                    String uri = row.getResource("uri").getURI();
                    EntityTypeBuilder builder = builders.computeIfAbsent(uri, EntityTypeBuilder::new);
                    if (row.contains("label")) {
                        builder.label = row.getLiteral("label").getString();
                    }
                    if (row.contains("super")) {
                        builder.superTypes.add(row.getResource("super").getURI());
                    }
                }
            }
            builders.values().forEach(b -> entities.add(b.build()));
            return entities;
        } finally {
            model.close();
        }
    }

    public List<RelationType> listRelations(String tenant, String version) {
        Model model = openModel(tenant, version);
        try {
            List<RelationType> relations = new ArrayList<>();
            String sparql = """
                    PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                    PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                    SELECT ?uri ?label ?domain ?range WHERE {
                      ?uri a rdf:Property .
                      OPTIONAL { ?uri rdfs:label ?label }
                      OPTIONAL { ?uri rdfs:domain ?domain }
                      OPTIONAL { ?uri rdfs:range ?range }
                    }
                    """;
            try (QueryExecution qexec = QueryExecutionFactory.create(QueryFactory.create(sparql), model)) {
                ResultSet rs = qexec.execSelect();
                while (rs.hasNext()) {
                    QuerySolution row = rs.nextSolution();
                    relations.add(new RelationType(
                            row.getResource("uri").getURI(),
                            row.contains("label") ? row.getLiteral("label").getString() : localName(row.getResource("uri")),
                            row.contains("domain") ? row.getResource("domain").getURI() : null,
                            row.contains("range") ? row.getResource("range").getURI() : null
                    ));
                }
            }
            return relations;
        } finally {
            model.close();
        }
    }

    public EntityType resolveEntity(String tenant, String version, String label) {
        return listEntities(tenant, version).stream()
                .filter(e -> label.equalsIgnoreCase(e.label()) || label.equalsIgnoreCase(e.uri()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Entity not found: " + label));
    }

    public RelationType resolveRelation(String tenant, String version, String label) {
        return listRelations(tenant, version).stream()
                .filter(r -> label.equalsIgnoreCase(r.label()) || label.equalsIgnoreCase(r.uri()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Relation not found: " + label));
    }

    public EntityType createEntity(String tenant, String version, String label, String superTypeLabel) {
        Model model = openModel(tenant, version);
        try {
            String baseUri = "http://synanton.example/ontology/demo#";
            String classUri = baseUri + sanitize(label);
            Resource entity = model.createResource(classUri);
            entity.addProperty(RDF.type, RDFS.Class);
            entity.addProperty(RDFS.label, label);
            if (superTypeLabel != null && !superTypeLabel.isBlank()) {
                EntityType superType = resolveEntity(tenant, version, superTypeLabel);
                entity.addProperty(RDFS.subClassOf, model.createResource(superType.uri()));
            }
            saveModel(tenant, version, model);
            return new EntityType(classUri, label, superTypeLabel != null ? List.of() : List.of(), Map.of());
        } finally {
            model.close();
        }
    }

    private Model openModel(String tenant, String version) {
        Path ttlFile = datasetPath(tenant, version).resolve("ontology.ttl");
        if (!Files.exists(ttlFile)) {
            throw new NotFoundException("Ontology version not found: " + version);
        }
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, ttlFile.toString(), Lang.TURTLE);
        return model;
    }

    private void saveModel(String tenant, String version, Model model) {
        Path ttlFile = datasetPath(tenant, version).resolve("ontology.ttl");
        try (OutputStream out = Files.newOutputStream(ttlFile)) {
            RDFDataMgr.write(out, model, Lang.TURTLE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write ontology file", e);
        }
    }

    private void writeVersionFile(String tenant, String version, String fileName, byte[] bytes) {
        Path datasetDir = datasetPath(tenant, version);
        try {
            Files.createDirectories(datasetDir);
            Files.write(datasetDir.resolve(fileName), bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist " + fileName + " for " + tenant + "/" + version, e);
        }
    }

    private Optional<byte[]> readVersionFile(String tenant, String version, String fileName) {
        Path file = datasetPath(tenant, version).resolve(fileName);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + fileName + " for " + tenant + "/" + version, e);
        }
    }

    private Path datasetPath(String tenant, String version) {
        return storageRoot.resolve(tenant).resolve(version);
    }

    private static String localName(Resource resource) {
        String uri = resource.getURI();
        if (uri == null) {
            return resource.toString();
        }
        int hash = uri.lastIndexOf('#');
        if (hash >= 0) {
            return uri.substring(hash + 1);
        }
        int slash = uri.lastIndexOf('/');
        return slash >= 0 ? uri.substring(slash + 1) : uri;
    }

    private static String sanitize(String label) {
        return label.replaceAll("\\s+", "");
    }

    public static class NotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NotFoundException(String message) {
            super(message);
        }
    }

    private static final class EntityTypeBuilder {
        private final String uri;
        private String label;
        private final Set<String> superTypes = new HashSet<>();

        private EntityTypeBuilder(String uri) {
            this.uri = uri;
            this.label = localNameFromUri(uri);
        }

        private EntityType build() {
            return new EntityType(uri, label, List.copyOf(superTypes), Map.of());
        }

        private static String localNameFromUri(String uri) {
            int hash = uri.lastIndexOf('#');
            if (hash >= 0) {
                return uri.substring(hash + 1);
            }
            int slash = uri.lastIndexOf('/');
            return slash >= 0 ? uri.substring(slash + 1) : uri;
        }
    }
}
