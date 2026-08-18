package org.synanton.synt.api.rest;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.synanton.synt.domain.model.EntityType;
import org.synanton.synt.domain.model.OntologyGraph;
import org.synanton.synt.domain.model.OntologyVersion;
import org.synanton.synt.domain.model.RelationType;
import org.synanton.synt.domain.service.OntologyLintService;
import org.synanton.synt.domain.service.OntologyService;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ontology")
public class OntologyController {

    private final OntologyService ontologyService;
    private final OntologyLintService lintService;

    public OntologyController(OntologyService ontologyService, OntologyLintService lintService) {
        this.ontologyService = ontologyService;
        this.lintService = lintService;
    }

    @GetMapping("/versions")
    public List<OntologyVersion> listVersions() {
        return ontologyService.listVersions();
    }

    @PostMapping(value = "/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public OntologyVersion createVersion(
            @RequestParam String version,
            @RequestParam(required = false) String label,
            @RequestParam(required = false) String description,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        return ontologyService.createVersion(version, label, description, file);
    }

    @GetMapping("/entities")
    public EntityType resolveEntity(
            @RequestParam String label,
            @RequestParam(defaultValue = "active") String version
    ) {
        return ontologyService.resolveEntity(label, version);
    }

    @GetMapping("/relations")
    public RelationType resolveRelation(
            @RequestParam String label,
            @RequestParam(defaultValue = "active") String version
    ) {
        return ontologyService.resolveRelation(label, version);
    }

    @GetMapping("/graph")
    public OntologyGraph getGraph(@RequestParam(defaultValue = "active") String version) {
        return ontologyService.getGraph(version);
    }

    @GetMapping("/lint")
    public Map<String, Object> lint(@RequestParam(defaultValue = "active") String version) {
        return lintService.lint(ontologyService.listEntities(version), ontologyService.listRelations(version));
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody ValidateRequest request) {
        return ontologyService.validateConcept(request.label(), request.uri());
    }

    public record ValidateRequest(String label, String uri) {
    }
}
