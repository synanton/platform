package org.synanton.syntology.domain.port.out;

import org.synanton.syntology.domain.model.Feature;
import org.synanton.syntology.domain.model.OntologyGraph;

import java.util.Optional;

public interface OntologyAdapter {

    void init(String storagePath);

    OntologyGraph loadOntology(String tenant, String version);

    void persistOntology(String tenant, String version, OntologyGraph graph);

    void persistTurtle(String tenant, String version, byte[] turtleBytes);

    void persistShapes(String tenant, String version, byte[] turtleBytes);

    void persistSchemaIr(String tenant, String version, byte[] jsonBytes);

    Optional<byte[]> loadShapes(String tenant, String version);

    Optional<byte[]> loadSchemaIr(String tenant, String version);

    boolean versionExists(String tenant, String version);

    void deleteVersion(String tenant, String version);

    boolean supportsFeature(Feature feature);
}
