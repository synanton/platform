package org.synanton.syntology.domain.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.synanton.common.fs.FsPermissionGuard;
import org.synanton.common.tenant.TenantContext;
import org.synanton.syntology.app.SyntologyProperties;
import org.synanton.syntology.domain.model.EntityType;
import org.synanton.syntology.domain.model.Feature;
import org.synanton.syntology.domain.model.OntologyGraph;
import org.synanton.syntology.domain.model.OntologyVersion;
import org.synanton.syntology.domain.model.RelationType;
import org.synanton.syntology.domain.port.out.EventPublisher;
import org.synanton.syntology.domain.port.out.MetadataRepository;
import org.synanton.syntology.domain.port.out.OntologyAdapter;
import org.synanton.syntology.infra.cache.EntityCache;
import org.synanton.syntology.infra.jena.JenaTdb2Adapter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OntologyService {

    private final OntologyAdapter ontologyAdapter;
    private final MetadataRepository metadataRepository;
    private final EntityCache entityCache;
    private final EventPublisher eventPublisher;
    private final SyntologyProperties properties;
    private final FsPermissionGuard fsPermissionGuard;

    public OntologyService(
            OntologyAdapter ontologyAdapter,
            MetadataRepository metadataRepository,
            EntityCache entityCache,
            EventPublisher eventPublisher,
            SyntologyProperties properties,
            FsPermissionGuard fsPermissionGuard
    ) {
        this.ontologyAdapter = ontologyAdapter;
        this.metadataRepository = metadataRepository;
        this.entityCache = entityCache;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.fsPermissionGuard = fsPermissionGuard;
    }

    public List<OntologyVersion> listVersions() {
        return metadataRepository.findAll(tenant());
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
        eventPublisher.publish("VERSION_BUMPED", "{\"version\":\"" + version + "\"}");
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
        eventPublisher.publish("ENTITY_CREATED", "{\"label\":\"" + label + "\"}");
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
