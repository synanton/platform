package org.synanton.annotations.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.synanton.annotations.domain.AnnotationDefinitionService;
import org.synanton.annotations.domain.DependencyGraphService;
import org.synanton.annotations.domain.model.AnnotationDefinition;
import org.synanton.annotations.domain.model.AnnotationDefinitionVersion;
import org.synanton.annotations.domain.model.DependencyEdge;

import java.util.List;

@RestController
public class DefinitionController {

    private final AnnotationDefinitionService definitions;
    private final DependencyGraphService dependencyGraph;

    public DefinitionController(AnnotationDefinitionService definitions, DependencyGraphService dependencyGraph) {
        this.definitions = definitions;
        this.dependencyGraph = dependencyGraph;
    }

    @PostMapping("/definitions")
    public ResponseEntity<AnnotationDefinition> createDefinition(@RequestBody CreateDefinitionRequest body) {
        AnnotationDefinition created = definitions.createDefinition(
                body.definitionId(), body.namespace(), body.name(), body.annotationType());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/definitions/{id}")
    public AnnotationDefinition getDefinition(@PathVariable("id") String id) {
        return definitions.getDefinition(id);
    }

    @PostMapping("/definitions/{id}/versions")
    public ResponseEntity<AnnotationDefinitionVersion> createVersion(
            @PathVariable("id") String id, @RequestBody CreateVersionRequest body) {
        AnnotationDefinitionVersion created = definitions.createVersion(
                id, body.version(), body.inputs(), body.producer(), body.producerVersion(),
                body.outputType(), body.outputName());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/definitions/{id}/versions/{version}")
    public AnnotationDefinitionVersion getVersion(@PathVariable("id") String id, @PathVariable("version") int version) {
        return definitions.getVersion(id, version);
    }

    @GetMapping("/definitions/{id}/versions")
    public List<AnnotationDefinitionVersion> listVersions(@PathVariable("id") String id) {
        return definitions.listVersions(id);
    }

    @PutMapping("/definitions/{id}/versions/{version}")
    public AnnotationDefinitionVersion updateVersion(
            @PathVariable("id") String id, @PathVariable("version") int version,
            @RequestBody CreateVersionRequest body) {
        return definitions.updateVersion(
                id, version, body.inputs(), body.producer(), body.producerVersion(),
                body.outputType(), body.outputName());
    }

    @PostMapping("/definitions/{id}/versions/{version}/publish")
    public AnnotationDefinitionVersion publish(@PathVariable("id") String id, @PathVariable("version") int version) {
        return definitions.publish(id, version);
    }

    @GetMapping("/definitions/published")
    public List<AnnotationDefinitionVersion> listPublished() {
        return definitions.listPublished();
    }

    @PostMapping("/definitions/{id}/versions/{version}/dependencies")
    public ResponseEntity<DependencyEdge> addDependency(
            @PathVariable("id") String id, @PathVariable("version") int version,
            @RequestBody AddDependencyRequest body) {
        DependencyEdge edge = dependencyGraph.addDependency(id, version, body.toDefinitionId(), body.toVersion());
        return ResponseEntity.status(HttpStatus.CREATED).body(edge);
    }

    public record CreateDefinitionRequest(String definitionId, String namespace, String name, String annotationType) {}

    public record CreateVersionRequest(
            int version, List<String> inputs, String producer, String producerVersion,
            String outputType, String outputName) {}

    public record AddDependencyRequest(String toDefinitionId, int toVersion) {}
}
