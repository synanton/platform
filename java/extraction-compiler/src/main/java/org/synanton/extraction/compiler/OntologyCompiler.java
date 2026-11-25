package org.synanton.extraction.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic, LLM-free compiler for an immutable ontology release. It consumes the stored
 * Syntology IR, emits an extraction SPEC and template, and records construct coverage.
 */
public final class OntologyCompiler {
    public static final String COMPILER_VERSION = "p0-1";
    public static final String SPEC_VERSION = "synanton.extraction.spec/v1";
    public static final String TEMPLATE_VERSION = "synanton.extraction.template/v1";
    public static final String REPORT_VERSION = "synanton.extraction.support/v1";

    private static final Set<String> SUPPORT_TYPES = Set.of(
        "Thing", "TopicBearing", "TextDivision", "DefinedConcept", "UsesSubject", "UsesObject",
        "DependencyNode", "PartOfSubject", "PartOfObject");
    private static final Set<String> SUPPORTED_DATATYPES = Set.of(
        "xsd:string", "xsd:integer", "xsd:int", "xsd:long", "xsd:boolean", "xsd:decimal",
        "xsd:double", "xsd:float", "xsd:date", "xsd:dateTime", "xsd:anyURI");

    public record CompileRequest(
        ReleaseIdentity release,
        JsonNode ir,
        String compilerVersion,
        String exportProfile,
        String schemaVersion,
        String deployedImage
    ) {
        public CompileRequest {
            Objects.requireNonNull(release, "release");
            Objects.requireNonNull(ir, "ir");
            require(compilerVersion, "compilerVersion");
            require(exportProfile, "exportProfile");
            require(schemaVersion, "schemaVersion");
        }
    }

    public record CompileResult(
        JsonNode spec,
        JsonNode template,
        JsonNode report,
        String specDigest,
        String templateDigest,
        String compilationId
    ) {
        public void writeTo(Path outputDir) {
            try {
                Files.createDirectories(outputDir);
                Files.writeString(outputDir.resolve("it-books.spec.json"),
                    CanonicalJson.pretty(spec), StandardCharsets.UTF_8);
                Files.writeString(outputDir.resolve("it-books.template.json"),
                    CanonicalJson.pretty(template), StandardCharsets.UTF_8);
                Files.writeString(outputDir.resolve("it-books.report.json"),
                    CanonicalJson.pretty(report), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Could not write compiler outputs to " + outputDir, e);
            }
        }
    }

    private record Property(String name, String path, String datatype, Integer minCount, Integer maxCount) {}

    private record ClassDef(String id, String label, List<String> superTypes, List<Property> properties) {}

    private record RelationDef(String id, String label, String domain, String range) {}

    public CompileResult compile(CompileRequest request) {
        JsonNode ir = request.ir();
        String ontologyId = text(ir.path("ontology"), "id", "ontology.id");
        if (!ontologyId.equals(request.release().ontologyId())) {
            throw new IllegalArgumentException("IR ontology " + ontologyId
                + " does not match release ontology " + request.release().ontologyId());
        }

        Map<String, ClassDef> classes = classes(ir);
        List<RelationDef> relations = relations(ir);
        Map<String, Set<String>> supers = superClosures(classes);

        List<Map<String, Object>> classCoverage = new ArrayList<>();
        List<Map<String, Object>> relationCoverage = new ArrayList<>();
        List<Map<String, Object>> unsupported = new ArrayList<>();
        List<Map<String, Object>> extractionEntities = new ArrayList<>();
        List<Map<String, Object>> relationTargets = new ArrayList<>();

        int supportClasses = 0;
        int extractionClasses = 0;
        int representableClasses = 0;
        int unsupportedClasses = 0;

        for (ClassDef cd : classes.values()) {
            boolean support = SUPPORT_TYPES.contains(cd.id());
            String status;
            String reason;
            if (support) {
                status = "SUPPORT";
                reason = "Marker/supertype used for domain/range expansion; not an extraction target.";
                supportClasses++;
            } else {
                extractionClasses++;
                List<String> bad = cd.properties().stream()
                    .map(Property::datatype)
                    .filter(dt -> dt == null || dt.isBlank() || !SUPPORTED_DATATYPES.contains(dt))
                    .toList();
                if (bad.isEmpty()) {
                    status = "REPRESENTABLE";
                    reason = "Concrete extraction type with supported or absent properties.";
                    representableClasses++;
                    extractionEntities.add(entity(cd));
                } else {
                    status = "UNSUPPORTED";
                    reason = "Unsupported property datatype(s): " + String.join(", ", bad);
                    unsupportedClasses++;
                    unsupported.add(Map.of("kind", "class", "id", cd.id(), "reason", reason));
                }
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", cd.id());
            entry.put("label", cd.label());
            entry.put("role", support ? "SUPPORT" : "EXTRACTION");
            entry.put("status", status);
            entry.put("reason", reason);
            entry.put("superTypes", cd.superTypes());
            entry.put("properties", cd.properties().stream().map(OntologyCompiler::propertyMap).toList());
            classCoverage.add(entry);
        }

        int representableRelations = 0;
        int adaptedRelations = 0;
        int unsupportedRelations = 0;
        for (RelationDef rd : relations) {
            List<String> domains = expand(rd.domain(), classes, supers);
            List<String> ranges = expand(rd.range(), classes, supers);
            String status;
            String reason;
            if (domains.isEmpty() || ranges.isEmpty()) {
                status = "UNSUPPORTED";
                reason = "Domain or range has no concrete extraction type.";
                unsupportedRelations++;
                unsupported.add(Map.of("kind", "relation", "id", rd.id(), "reason", reason));
            } else if (SUPPORT_TYPES.contains(rd.domain()) || SUPPORT_TYPES.contains(rd.range())
                || domains.size() != 1 || ranges.size() != 1) {
                status = "ADAPTED";
                reason = "Marker or multi-type domain/range expanded to concrete extraction types.";
                adaptedRelations++;
            } else {
                status = "REPRESENTABLE";
                reason = "Concrete one-to-one domain/range.";
                representableRelations++;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", rd.id());
            entry.put("label", rd.label());
            entry.put("status", status);
            entry.put("reason", reason);
            entry.put("domain", rd.domain());
            entry.put("range", rd.range());
            entry.put("subjects", domains);
            entry.put("objects", ranges);
            relationCoverage.add(entry);
            relationTargets.add(relationTarget(rd, status, domains, ranges));
        }

        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("tenantId", request.release().tenantId());
        provenance.put("ontologyId", request.release().ontologyId());
        provenance.put("version", request.release().version());
        provenance.put("releaseId", request.release().releaseId());
        provenance.put("releaseDigest", request.release().releaseDigest());
        provenance.put("irDigest", CanonicalJson.sha256(ir));
        provenance.put("compilerVersion", request.compilerVersion());
        provenance.put("exportProfile", request.exportProfile());
        provenance.put("schemaVersion", request.schemaVersion());
        provenance.put("canonicalization", CanonicalJson.CANONICALIZATION);
        provenance.put("llmUsed", false);

        ObjectNode spec = CanonicalJson.object();
        spec.put("specVersion", SPEC_VERSION);
        spec.set("provenance", CanonicalJson.mapper().valueToTree(provenance));
        spec.set("entities", CanonicalJson.mapper().valueToTree(extractionEntities));
        spec.set("relations", CanonicalJson.mapper().valueToTree(relationCoverage.stream()
            .map(r -> Map.of(
                "id", r.get("id"), "label", r.get("label"), "status", r.get("status"),
                "subjects", r.get("subjects"), "objects", r.get("objects")))
            .toList()));

        ObjectNode template = CanonicalJson.object();
        template.put("templateVersion", TEMPLATE_VERSION);
        template.set("provenance", CanonicalJson.mapper().valueToTree(provenance));
        template.set("entityTargets", CanonicalJson.mapper().valueToTree(
            extractionEntities.stream().map(e -> Map.of(
                "type", e.get("id"), "label", e.get("label"), "fields", e.get("properties"))).toList()));
        template.set("relationTargets", CanonicalJson.mapper().valueToTree(relationTargets));

        String specDigest = CanonicalJson.sha256(spec);
        String templateDigest = CanonicalJson.sha256(template);
        String compilationId = CanonicalJson.sha256(String.join("|",
            request.release().releaseDigest(), request.compilerVersion(), request.exportProfile(),
            request.schemaVersion(), CanonicalJson.sha256(ir)));

        Map<String, Object> constructs = new LinkedHashMap<>();
        constructs.put("classesTotal", classes.size());
        constructs.put("classesSupport", supportClasses);
        constructs.put("classesExtraction", extractionClasses);
        constructs.put("classesRepresentable", representableClasses);
        constructs.put("classesUnsupported", unsupportedClasses);
        constructs.put("relationsTotal", relations.size());
        constructs.put("relationsRepresentable", representableRelations);
        constructs.put("relationsAdapted", adaptedRelations);
        constructs.put("relationsUnsupported", unsupportedRelations);

        Map<String, Object> reportProvenance = new LinkedHashMap<>(provenance);
        reportProvenance.put("deployedImage",
            request.deployedImage() == null ? "" : request.deployedImage());

        ObjectNode report = CanonicalJson.object();
        report.put("reportVersion", REPORT_VERSION);
        report.set("provenance", CanonicalJson.mapper().valueToTree(reportProvenance));
        report.set("coverage", CanonicalJson.mapper().valueToTree(Map.of(
            "classes", classCoverage, "relations", relationCoverage)));
        report.set("constructs", CanonicalJson.mapper().valueToTree(constructs));
        report.set("unsupported", CanonicalJson.mapper().valueToTree(unsupported));
        report.set("digests", CanonicalJson.mapper().valueToTree(Map.of(
            "specDigest", specDigest,
            "templateDigest", templateDigest,
            "compilationId", compilationId)));
        report.put("canonicalization", CanonicalJson.CANONICALIZATION);

        return new CompileResult(spec, template, report, specDigest, templateDigest, compilationId);
    }

    private static Map<String, ClassDef> classes(JsonNode ir) {
        Map<String, ClassDef> result = new LinkedHashMap<>();
        for (JsonNode node : ir.path("classes")) {
            String id = text(node, "id", "class.id");
            List<String> supers = strings(node.path("superTypes"));
            List<Property> properties = new ArrayList<>();
            for (JsonNode p : node.path("properties")) {
                properties.add(new Property(text(p, "name", "property.name"), p.path("path").asText(null),
                    p.path("datatype").asText(null), integer(p, "minCount"), integer(p, "maxCount")));
            }
            if (result.put(id, new ClassDef(id, node.path("label").asText(id), supers, properties)) != null) {
                throw new IllegalArgumentException("duplicate class id: " + id);
            }
        }
        return result;
    }

    private static List<RelationDef> relations(JsonNode ir) {
        List<RelationDef> result = new ArrayList<>();
        for (JsonNode node : ir.path("relations")) {
            result.add(new RelationDef(text(node, "id", "relation.id"),
                node.path("label").asText(text(node, "id", "relation.id")),
                text(node, "domain", "relation.domain"), text(node, "range", "relation.range")));
        }
        return result;
    }

    private static Map<String, Set<String>> superClosures(Map<String, ClassDef> classes) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String id : classes.keySet()) {
            result.put(id, closure(id, classes, new LinkedHashSet<>()));
        }
        return result;
    }

    private static Set<String> closure(String id, Map<String, ClassDef> classes, Set<String> seen) {
        seen.add(id);
        ClassDef def = classes.get(id);
        if (def != null) {
            for (String parent : def.superTypes()) {
                if (!seen.contains(parent)) {
                    closure(parent, classes, seen);
                }
            }
        }
        return seen;
    }

    private static List<String> expand(String type, Map<String, ClassDef> classes,
                                       Map<String, Set<String>> supers) {
        if (type == null || type.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (ClassDef cd : classes.values()) {
            if (SUPPORT_TYPES.contains(cd.id())) {
                continue;
            }
            if (cd.id().equals(type) || supers.getOrDefault(cd.id(), Set.of()).contains(type)) {
                result.add(cd.id());
            }
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private static Map<String, Object> entity(ClassDef cd) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", cd.id());
        entity.put("label", cd.label());
        entity.put("superTypes", cd.superTypes());
        entity.put("properties", cd.properties().stream().map(OntologyCompiler::propertyMap).toList());
        return entity;
    }

    private static Map<String, Object> relationTarget(RelationDef rd, String status,
                                                      List<String> domains, List<String> ranges) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("predicate", rd.id());
        target.put("label", rd.label());
        target.put("status", status);
        target.put("subjects", domains);
        target.put("objects", ranges);
        return target;
    }

    private static Map<String, Object> propertyMap(Property p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", p.name());
        map.put("path", p.path() == null ? "" : p.path());
        map.put("datatype", p.datatype() == null ? "" : p.datatype());
        map.put("minCount", p.minCount());
        map.put("maxCount", p.maxCount());
        return map;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static String text(JsonNode node, String field, String name) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("missing " + name);
        }
        return value.asText();
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
