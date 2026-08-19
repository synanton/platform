package org.synanton.syntology.api.rest;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.synanton.syntology.domain.model.OntologyVersion;
import org.synanton.syntology.domain.model.schema.OntologySchemaIr;
import org.synanton.syntology.domain.service.SchemaLoadService;
import org.synanton.syntology.infra.schema.IncludeResolver;
import org.synanton.syntology.infra.schema.SchemaBundleUnpacker;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/ontology")
public class OntologyAdminController {

    private final SchemaLoadService schemaLoadService;

    public OntologyAdminController(SchemaLoadService schemaLoadService) {
        this.schemaLoadService = schemaLoadService;
    }

    @PostMapping(value = "/schemas", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public OntologyVersion loadBundle(
            @RequestParam String version,
            @RequestParam(required = false) String label,
            @RequestParam(required = false) String description,
            @RequestParam(defaultValue = "schema.hcl") String entry,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        Path bundle = unpack(file);
        return schemaLoadService.persist(bundle, entry, version, label, description);
    }

    @PostMapping(value = "/schemas/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> previewBundle(
            @RequestParam(defaultValue = "schema.hcl") String entry,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        Path bundle = unpack(file);
        return schemaLoadService.preview(bundle, entry);
    }

    @PostMapping("/schemas/from-path")
    public OntologyVersion loadFromGitPath(@RequestBody FromPathRequest request) throws IOException {
        Path gitRoot = schemaLoadService.gitRoot();
        Path bundle = gitRoot.resolve(request.relativePath()).normalize();
        IncludeResolver.ensureInside(gitRoot, bundle.resolve(request.entryOrDefault()).toAbsolutePath().normalize());
        return schemaLoadService.persist(
                bundle,
                request.entryOrDefault(),
                request.version(),
                request.label(),
                request.description()
        );
    }

    @GetMapping("/schemas/{version}")
    public OntologySchemaIr getStored(@PathVariable String version) {
        return schemaLoadService.loadStoredIr(version);
    }

    private Path unpack(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "schema.zip";
        if (name.endsWith(".zip")) {
            return SchemaBundleUnpacker.unpackZip(file.getBytes());
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(name, file.getBytes());
        return SchemaBundleUnpacker.writeFiles(files);
    }

    public record FromPathRequest(
            String relativePath,
            String version,
            String entry,
            String label,
            String description
    ) {
        String entryOrDefault() {
            return entry == null || entry.isBlank() ? "schema.hcl" : entry;
        }
    }
}
