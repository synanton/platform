package org.synanton.synquest.ontology;

import org.springframework.lang.Nullable;
import org.synanton.common.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Demo-only ontology read endpoints. */
@RestController
@RequestMapping("/v1/demo/ontology")
public class OntologyController {

    private static final Set<String> VALID_TYPES = Set.of(
            "all", "identifier", "version", "date", "section_ref", "case_name",
            "glossary", "filenet", "legal_doctrine", "glossary_term", "doctrinal_term",
            "hard", "soft", "soft_glossary", "soft_doctrinal");
    private static final Set<String> VALID_BANDS = Set.of(
            "all", "graph_useful", "df_1", "pair", "df_3_15", "df_16_50", "df_over_50");
    private static final Set<String> VALID_CLASSES = Set.of(
            "hard", "soft", "soft_glossary", "soft_doctrinal");

    private final OntologyMapper mapper;

    public OntologyController(OntologyMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping("/{type}")
    public ResponseEntity<?> list(
            @PathVariable String type,
            @RequestParam(name = "df_band", required = false) @Nullable String dfBand,
            @RequestParam(name = "entity_class", required = false) @Nullable String entityClass) {
        requireTenant();
        if (!VALID_TYPES.contains(type)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "unknown type", "valid", VALID_TYPES));
        }
        if (dfBand != null && !VALID_BANDS.contains(dfBand)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "unknown df_band", "valid", VALID_BANDS));
        }
        if (entityClass != null && !VALID_CLASSES.contains(entityClass)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "unknown entity_class", "valid", VALID_CLASSES));
        }

        List<OntologyEntity> results = mapper.filter(type, dfBand, entityClass);
        return ResponseEntity.ok(Map.of("entities", results, "count", results.size()));
    }

    @GetMapping("/entity/{id}")
    public ResponseEntity<?> getOne(@PathVariable String id) {
        requireTenant();
        return mapper.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "entity not found", "id", id)));
    }

    private static void requireTenant() {
        if (TenantContext.get() == null) {
            throw new IllegalStateException("Verified tenant context is required");
        }
    }
}
