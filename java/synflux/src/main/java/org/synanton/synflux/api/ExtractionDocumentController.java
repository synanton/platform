package org.synanton.synflux.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.synanton.common.tenant.TenantContext;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ExtractionResultRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;
import org.synanton.ingestioncache.quarantine.QuarantineGeneration;
import org.synanton.ingestioncache.quarantine.QuarantineRecord;
import org.synanton.ingestioncache.quarantine.QuarantineStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Phase 5 read/promote surface for one source-version extraction document. */
@RestController
@RequestMapping("/v1/extraction/documents")
public class ExtractionDocumentController {

    private final IngestionCacheClient cache;
    private final QuarantineStore quarantineStore;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExtractionDocumentController(IngestionCacheClient cache, QuarantineStore quarantineStore) {
        this.cache = cache;
        this.quarantineStore = quarantineStore;
    }

    @GetMapping
    public DocumentView document(@RequestParam String connectorId,
                                 @RequestParam String sourceId,
                                 @RequestParam String resourceId,
                                 @RequestParam UUID versionId) {
        String tenant = tenant();
        SourceVersionRow version = cache.readSourceVersion(tenant, connectorId, sourceId,
                        resourceId, versionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "source version not found"));
        List<QuarantineGeneration> generations = quarantineStore.generations(tenant, versionId);
        List<QuarantineRecord> records = quarantineStore.records(tenant, versionId);
        List<ExtractionResultRow> results = cache.listExtractionResults(tenant,
                version.contentRefId());
        return new DocumentView(tenant, connectorId, sourceId, resourceId, versionId,
                version.contentRefId(),
                quarantineStore.currentGeneration(tenant, versionId).orElse(null),
                generations.stream().map(this::generation).toList(),
                results.stream().map(this::result).toList(),
                records.stream().map(this::quarantine).toList());
    }

    @PostMapping("/promote")
    public PromotionView promote(@RequestBody PromotionRequest request) {
        String tenant = tenant();
        if (request == null || request.versionId() == null || request.generationId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "versionId and generationId are required");
        }
        cache.readSourceVersion(tenant, request.connectorId(), request.sourceId(),
                        request.resourceId(), request.versionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "source version not found"));
        boolean known = quarantineStore.generations(tenant, request.versionId()).stream()
                .anyMatch(row -> row.extractionGeneration().equals(request.generationId()));
        if (!known) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "generation not found");
        }
        String actor = TenantContext.get() == null || TenantContext.get().subject() == null
                ? "user" : TenantContext.get().subject();
        var result = quarantineStore.promote(tenant, request.versionId(), request.generationId(),
                actor, request.reason() == null ? "ui promotion" : request.reason());
        return new PromotionView(result.currentGeneration(), result.applied());
    }

    private String tenant() {
        TenantContext context = TenantContext.get();
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "tenant context required");
        }
        return context.tenantId();
    }

    private GenerationView generation(QuarantineGeneration row) {
        return new GenerationView(row.extractionGeneration(), row.generationStatus(),
                row.ontologyReleaseId(), row.ontologyReleaseDigest(), row.promptVersion(),
                row.promptDigest(), row.schemaDigest(), row.modelId(), row.modelRevision(),
                row.extractionStartedAt(), row.extractionRunId());
    }

    private ResultView result(ExtractionResultRow row) {
        return new ResultView(row.chunkOrdinal(), readTree(row.entitiesJson()),
                readTree(row.relationsJson()), row.promptVersion(), row.promptCompiledDigest(),
                row.promptRenderedDigest(), row.schemaVersion(), row.schemaDigest(),
                row.extractionGeneration(), row.extractedAt());
    }

    private QuarantineView quarantine(QuarantineRecord row) {
        return new QuarantineView(row.extractionGeneration(), row.chunkOrdinal(),
                row.lineNumber(), row.reasonCode(), row.category(), row.detail(),
                row.disposition(), row.retentionExpiresAt());
    }

    private JsonNode readTree(String json) {
        try {
            return json == null ? mapper.createArrayNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }

    public record DocumentView(String tenantId, String connectorId, String sourceId,
                               String resourceId, UUID versionId, UUID contentRefId,
                               UUID currentGeneration, List<GenerationView> generations,
                               List<ResultView> results, List<QuarantineView> quarantine) {}

    public record GenerationView(UUID generationId, String status, String ontologyReleaseId,
                                 String ontologyReleaseDigest, String promptVersion,
                                 String promptCompiledDigest, String schemaDigest,
                                 String modelId, String modelRevision, Instant startedAt,
                                 UUID extractionRunId) {}

    public record ResultView(int chunkOrdinal, JsonNode entities, JsonNode relations,
                             String promptVersion, String promptCompiledDigest,
                             String promptRenderedDigest, String schemaVersion,
                             String schemaDigest, UUID extractionGeneration,
                             Instant extractedAt) {}

    public record QuarantineView(UUID generationId, int chunkOrdinal, int lineNumber,
                                 String reasonCode, String category, String detail,
                                 String disposition, Instant retentionExpiresAt) {}

    public record PromotionRequest(String connectorId, String sourceId, String resourceId,
                                   UUID versionId, UUID generationId, String reason) {}

    public record PromotionView(UUID currentGeneration, boolean applied) {}
}
