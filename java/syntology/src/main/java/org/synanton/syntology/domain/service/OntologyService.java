package org.synanton.syntology.domain.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.synanton.common.fs.FsPermissionGuard;
import org.synanton.common.tenant.TenantContext;
import org.synanton.syntology.app.SyntologyProperties;
import org.synanton.syntology.domain.event.EntityCreated;
import org.synanton.syntology.domain.event.VersionBumped;
import org.synanton.syntology.domain.event.VersionBumpSource;
import org.synanton.syntology.domain.model.EntityType;
import org.synanton.syntology.domain.model.Feature;
import org.synanton.syntology.domain.model.OntologyGraph;
import org.synanton.syntology.domain.model.OntologyVersion;
import org.synanton.syntology.domain.model.RelationType;
import org.synanton.syntology.domain.port.out.MetadataRepository;
import org.synanton.syntology.domain.port.out.OntologyAdapter;
import org.synanton.syntology.infra.cache.EntityCache;
import org.synanton.syntology.infra.events.SyntologyEventPublisher;
import org.synanton.syntology.infra.jena.JenaTdb2Adapter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.validation.ReportEntry;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class OntologyService {

    private final OntologyAdapter ontologyAdapter;
    private final MetadataRepository metadataRepository;
    private final EntityCache entityCache;
    private final SyntologyEventPublisher events;
    private final SyntologyProperties properties;
    private final FsPermissionGuard fsPermissionGuard;

    public OntologyService(
            OntologyAdapter ontologyAdapter,
            MetadataRepository metadataRepository,
            EntityCache entityCache,
            SyntologyEventPublisher events,
            SyntologyProperties properties,
            FsPermissionGuard fsPermissionGuard
    ) {
        this.ontologyAdapter = ontologyAdapter;
        this.metadataRepository = metadataRepository;
        this.entityCache = entityCache;
        this.events = events;
        this.properties = properties;
        this.fsPermissionGuard = fsPermissionGuard;
    }

    public List<OntologyVersion> listVersions() {
        return metadataRepository.findAll(tenant());
    }

    /** Active ontology version for the verified tenant, if one exists. */
    public java.util.Optional<OntologyVersion> activeVersion(String tenantId) {
        return metadataRepository.findActive(tenantId);
    }

    public OntologyVersion createVersion(
            String version,
            String label,
            String description,
            MultipartFile file
    ) throws IOException {
        String tenant = tenant();
        checkWritePermission(tenant);
        byte[] bytes = file.getBytes();
        metadataRepository.deprecateAllActive(tenant);
        String graphUri = properties.storage().jena().path() + "/" + tenant + "/" + version;
        ontologyAdapter.persistTurtle(tenant, version, bytes);
        OntologyVersion ontologyVersion = new OntologyVersion(
                UUID.randomUUID(),
                tenant,
                version,
                label != null ? label : version,
                description,
                graphUri,
                "ACTIVE",
                Instant.now()
        );
        metadataRepository.insert(ontologyVersion);
        entityCache.invalidateAll();
        events.publishVersionBumped(tenant, new VersionBumped(ontologyVersion.versionId(), version, VersionBumpSource.ONTOLOGY));
        return ontologyVersion;
    }

    public EntityType resolveEntity(String label, String version) {
        String tenant = tenant();
        String resolvedVersion = resolveVersion(version);
        String cacheKey = EntityCache.key(tenant, resolvedVersion, label);
        return entityCache.get(cacheKey).orElseGet(() -> {
            EntityType entity = jena().resolveEntity(tenant, resolvedVersion, label);
            entityCache.put(cacheKey, entity);
            return entity;
        });
    }

    public RelationType resolveRelation(String label, String version) {
        String tenant = tenant();
        String resolvedVersion = resolveVersion(version);
        return jena().resolveRelation(tenant, resolvedVersion, label);
    }

    public OntologyGraph getGraph(String version) {
        String tenant = tenant();
        String resolvedVersion = resolveVersion(version);
        return ontologyAdapter.loadOntology(tenant, resolvedVersion);
    }

    public Map<String, Object> validateConcept(String label, String uri) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean basic = label != null && !label.isBlank() && uri != null && !uri.isBlank();
        if (!basic) {
            result.put("valid", false);
            result.put("message", "Entity must have both label and URI");
            return result;
        }
        String tenant = tenant();
        String version = resolveVersion("active");
        var shapesTurtle = ontologyAdapter.loadShapes(tenant, version);
        if (shapesTurtle.isEmpty()) {
            result.put("valid", true);
            result.put("message", "Basic validation passed");
            return result;
        }
        EntityType entity = resolveEntity(label, version);
        Model data = ModelFactory.createDefaultModel();
        Resource node = data.createResource(uri);
        node.addProperty(RDF.type, data.createResource(entity.uri()));
        node.addProperty(RDFS.label, label);
        Model shapeModel = ModelFactory.createDefaultModel();
        RDFDataMgr.read(shapeModel, new ByteArrayInputStream(shapesTurtle.get()), Lang.TURTLE);
        Shapes shapes = Shapes.parse(shapeModel);
        ValidationReport report = ShaclValidator.get().validate(shapes, data.getGraph());
        result.put("valid", report.conforms());
        result.put("conforms", report.conforms());
        result.put("message", report.conforms() ? "SHACL validation passed" : "SHACL validation failed");
        return result;
    }

    /**
     * Relation-level validation for RAKG PR #3. Domain/range are checked from the
     * TBox; SHACL property-shape violations for the predicate are included when the
     * ontology declares them. Unknown types/predicates are structured violations,
     * not exceptions. Tenant comes from the verified context.
     */
    public Map<String, Object> validateRelation(
            String subjectType, String predicate, String objectType, String ontologyVersion) {
        String tenant = tenant();
        String version = resolveVersion(
                ontologyVersion == null || ontologyVersion.isBlank() ? "active" : ontologyVersion);
        List<Map<String, Object>> violations = new java.util.ArrayList<>();
        EntityType subject = null;
        EntityType object = null;
        RelationType relation = null;

        if (subjectType == null || subjectType.isBlank()) {
            violations.add(violation("missing_subject_type", "subjectType is required", null));
        } else {
            try {
                subject = resolveEntity(subjectType, version);
            } catch (JenaTdb2Adapter.NotFoundException e) {
                violations.add(violation("unknown_subject_type",
                        "Unknown subject type: " + subjectType, subjectType));
            }
        }
        if (predicate == null || predicate.isBlank()) {
            violations.add(violation("missing_predicate", "predicate is required", null));
        } else {
            try {
                relation = resolveRelation(predicate, version);
            } catch (JenaTdb2Adapter.NotFoundException e) {
                violations.add(violation("unknown_predicate", "Unknown predicate: " + predicate, predicate));
            }
        }
        if (objectType == null || objectType.isBlank()) {
            violations.add(violation("missing_object_type", "objectType is required", null));
        } else {
            try {
                object = resolveEntity(objectType, version);
            } catch (JenaTdb2Adapter.NotFoundException e) {
                violations.add(violation("unknown_object_type",
                        "Unknown object type: " + objectType, objectType));
            }
        }

        if (relation != null && subject != null && relation.domain() != null
                && !typeMatches(subject, relation.domain(), tenant, version)) {
            violations.add(violation("domain_mismatch",
                    "Subject type " + subject.label() + " is not compatible with domain "
                            + relation.domain(), relation.domain()));
        }
        if (relation != null && object != null && relation.range() != null
                && !typeMatches(object, relation.range(), tenant, version)) {
            violations.add(violation("range_mismatch",
                    "Object type " + object.label() + " is not compatible with range "
                            + relation.range(), relation.range()));
        }

        if (relation != null && subject != null && object != null) {
            var shapesTurtle = ontologyAdapter.loadShapes(tenant, version);
            if (shapesTurtle.isPresent()) {
                Model data = ModelFactory.createDefaultModel();
                Resource s = data.createResource("urn:synanton:subject");
                Resource o = data.createResource("urn:synanton:object");
                s.addProperty(RDF.type, data.createResource(subject.uri()));
                s.addProperty(RDFS.label, subject.label());
                o.addProperty(RDF.type, data.createResource(object.uri()));
                o.addProperty(RDFS.label, object.label());
                s.addProperty(data.createProperty(relation.uri()), o);
                Model shapeModel = ModelFactory.createDefaultModel();
                RDFDataMgr.read(shapeModel, new ByteArrayInputStream(shapesTurtle.get()), Lang.TURTLE);
                Shapes shapes = Shapes.parse(shapeModel);
                ValidationReport report = ShaclValidator.get().validate(shapes, data.getGraph());
                for (ReportEntry entry : report.getEntries()) {
                    String path = entry.resultPath() == null ? null : entry.resultPath().toString();
                    // Only relation-shape violations; entity property completeness is the
                    // concept validator's job, not a reason to reject the candidate edge.
                    if (path == null || !path.equals(relation.uri())) {
                        continue;
                    }
                    violations.add(violation("shacl_violation",
                            entry.message() == null ? "SHACL relation violation" : entry.message(), path));
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conformant", violations.isEmpty());
        result.put("valid", violations.isEmpty());
        result.put("violations", violations);
        result.put("subject_type", subjectType);
        result.put("predicate", predicate);
        result.put("object_type", objectType);
        result.put("version", version);
        return result;
    }

    private boolean typeMatches(EntityType type, String requiredUri, String tenant, String version) {
        if (requiredUri.equals(type.uri())) {
            return true;
        }
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        if (type.superTypes() != null) {
            queue.addAll(type.superTypes());
        }
        while (!queue.isEmpty()) {
            String candidate = queue.poll();
            if (!visited.add(candidate)) {
                continue;
            }
            if (requiredUri.equals(candidate)) {
                return true;
            }
            try {
                EntityType parent = jena().resolveEntity(tenant, version, candidate);
                if (parent.superTypes() != null) {
                    queue.addAll(parent.superTypes());
                }
            } catch (JenaTdb2Adapter.NotFoundException ignored) {
                // Unknown intermediate supertype: keep checking the rest.
            }
        }
        return false;
    }

    private static Map<String, Object> violation(String code, String message, String path) {
        Map<String, Object> violation = new LinkedHashMap<>();
        violation.put("code", code);
        violation.put("message", message);
        if (path != null) {
            violation.put("path", path);
        }
        return violation;
    }

    public Map<String, Object> getCapabilities() {
        Map<String, String> features = new LinkedHashMap<>();
        for (Feature feature : Feature.values()) {
            features.put(feature.name(), ontologyAdapter.supportsFeature(feature) ? "NATIVE" : "UNSUPPORTED");
        }
        features.put("SESSION_PINNING", "NATIVE");
        features.put("MULTI_TENANT_VERSIONING", "NATIVE");
        features.put("CROSS_TENANT_INHERITANCE", "NOT_SUPPORTED");
        return Map.of(
                "module_id", "syntology",
                "module_version", "0.1.0",
                "features", features
        );
    }

    public List<EntityType> listEntities(String version) {
        return jena().listEntities(tenant(), resolveVersion(version));
    }

    public List<RelationType> listRelations(String version) {
        return jena().listRelations(tenant(), resolveVersion(version));
    }

    public EntityType createEntity(String label, String superType) {
        String tenant = tenant();
        String version = resolveVersion("active");
        checkWritePermission(tenant);
        EntityType created = jena().createEntity(tenant, version, label, superType);
        entityCache.invalidateAll();
        // TODO(#485): Two non-atomic cases in syntology:
        //   1. ENTITY_CREATED (this method): Jena TDB2 cannot share the JDBC Connection.
        //      Permanent 4-arg path — will not be fixed by #485.
        //   2. VERSION_BUMPED (createVersion / persist): MetadataRepository uses JdbcTemplate
        //      (connection-per-op). Same-transaction requires the Connection-scoped refactor in #485.
        // Until #485 lands, metadata writes and VersionBumped publication are not atomic.
        events.publishEntityCreated(tenant, new EntityCreated(label));
        return created;
    }

    private void checkWritePermission(String tenant) {
        if (!TenantContext.isAuthenticated()) return;
        Path ontologyPath = Path.of(properties.ontologyDir(), tenant);
        if (Files.exists(ontologyPath)) {
            fsPermissionGuard.checkWrite(ontologyPath, TenantContext.get().uid(), TenantContext.get().gids());
        }
    }

    private String resolveVersion(String version) {
        if (version == null || version.isBlank() || "active".equalsIgnoreCase(version)) {
            return metadataRepository.findActive(tenant())
                    .map(OntologyVersion::version)
                    .orElseThrow(() -> new IllegalStateException("No active ontology version"));
        }
        return metadataRepository.findByVersion(tenant(), version)
                .map(OntologyVersion::version)
                .orElseThrow(() -> new JenaTdb2Adapter.NotFoundException("Version not found: " + version));
    }

    private JenaTdb2Adapter jena() {
        return (JenaTdb2Adapter) ontologyAdapter;
    }

    private String tenant() {
        TenantContext ctx = TenantContext.get();
        return ctx != null ? ctx.tenantId() : properties.tenant().defaultId();
    }
}
