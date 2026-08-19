package org.synanton.syntology.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.synanton.common.tenant.TenantContext;
import org.synanton.syntology.app.SyntologyProperties;
import org.synanton.syntology.domain.model.OntologyVersion;
import org.synanton.syntology.domain.model.schema.OntologySchemaIr;
import org.synanton.syntology.domain.port.out.EventPublisher;
import org.synanton.syntology.domain.port.out.MetadataRepository;
import org.synanton.syntology.domain.port.out.OntologyAdapter;
import org.synanton.syntology.infra.cache.EntityCache;
import org.synanton.syntology.infra.jena.ShaclRuntimeMapper;
import org.synanton.syntology.infra.jena.TboxRuntimeMapper;
import org.synanton.syntology.infra.schema.IncludeResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class SchemaLoadService {

    private final IncludeResolver includeResolver;
    private final TboxRuntimeMapper tboxMapper;
    private final ShaclRuntimeMapper shaclMapper;
    private final OntologyAdapter ontologyAdapter;
    private final MetadataRepository metadataRepository;
    private final EntityCache entityCache;
    private final EventPublisher eventPublisher;
    private final SyntologyProperties properties;
    private final ObjectMapper objectMapper;

    public SchemaLoadService(
            IncludeResolver includeResolver,
            TboxRuntimeMapper tboxMapper,
            ShaclRuntimeMapper shaclMapper,
            OntologyAdapter ontologyAdapter,
            MetadataRepository metadataRepository,
            EntityCache entityCache,
            EventPublisher eventPublisher,
            SyntologyProperties properties,
            ObjectMapper objectMapper
    ) {
        this.includeResolver = includeResolver;
        this.tboxMapper = tboxMapper;
        this.shaclMapper = shaclMapper;
        this.ontologyAdapter = ontologyAdapter;
        this.metadataRepository = metadataRepository;
        this.entityCache = entityCache;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public OntologySchemaIr compile(Path bundleRoot, String entryFile) {
        Path entry = bundleRoot.resolve(entryFile == null || entryFile.isBlank() ? "schema.hcl" : entryFile);
        return includeResolver.resolve(bundleRoot, entry);
    }

    public Map<String, Object> preview(Path bundleRoot, String entryFile) {
        OntologySchemaIr ir = compile(bundleRoot, entryFile);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ir", ir);
        body.put("tbox_turtle", tboxMapper.toTurtleString(ir));
        body.put("shapes_turtle", shaclMapper.toTurtleString(ir));
        return body;
    }

    public OntologyVersion persist(Path bundleRoot, String entryFile, String version, String label, String description)
            throws IOException {
        OntologySchemaIr ir = compile(bundleRoot, entryFile);
        String tenant = tenant();
        metadataRepository.deprecateAllActive(tenant);
        ontologyAdapter.persistTurtle(tenant, version, tboxMapper.toTurtle(ir));
        ontologyAdapter.persistShapes(tenant, version, shaclMapper.toTurtle(ir));
        ontologyAdapter.persistSchemaIr(tenant, version, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(ir));
        String graphUri = properties.storage().jena().path() + "/" + tenant + "/" + version;
        String resolvedLabel = label != null ? label : (ir.ontology() != null && ir.ontology().label() != null
                ? ir.ontology().label() : version);
        OntologyVersion ontologyVersion = new OntologyVersion(
                UUID.randomUUID(),
                tenant,
                version,
                resolvedLabel,
                description != null ? description : ir.ontology() != null ? ir.ontology().description() : null,
                graphUri,
                "ACTIVE",
                Instant.now()
        );
        metadataRepository.insert(ontologyVersion);
        entityCache.invalidateAll();
        eventPublisher.publish("VERSION_BUMPED", "{\"version\":\"" + version + "\",\"source\":\"hcl\"}");
        return ontologyVersion;
    }

    public OntologySchemaIr loadStoredIr(String version) {
        String resolved = resolveVersion(version);
        byte[] json = ontologyAdapter.loadSchemaIr(tenant(), resolved)
                .orElseThrow(() -> new IllegalArgumentException("No HCL schema IR stored for version " + resolved));
        try {
            return objectMapper.readValue(json, OntologySchemaIr.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Stored schema IR is not valid JSON", ex);
        }
    }

    public Path gitRoot() {
        return Path.of(properties.schema().gitRoot()).toAbsolutePath().normalize();
    }

    private String resolveVersion(String version) {
        if (version == null || version.isBlank() || "active".equalsIgnoreCase(version)) {
            return metadataRepository.findActive(tenant())
                    .map(OntologyVersion::version)
                    .orElseThrow(() -> new IllegalStateException("No active ontology version"));
        }
        return metadataRepository.findByVersion(tenant(), version)
                .map(OntologyVersion::version)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + version));
    }

    private String tenant() {
        TenantContext ctx = TenantContext.get();
        return ctx != null ? ctx.tenantId() : properties.tenant().defaultId();
    }
}
