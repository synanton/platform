package org.synanton.synflux.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.synanton.common.context.RequestContext;
import org.synanton.common.context.RequestContextHolder;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.SourceIdentityRow;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;
import org.synanton.synflux.lifecycle.FixtureSourceWorkflow;
import org.synanton.synflux.lifecycle.FilesystemFixturePolicy;
import org.synanton.synflux.lifecycle.FixtureSourceWorkflow.Submission;
import org.synanton.synflux.security.TrustedRequestContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/v1/sources")
public class SourceController {

    private final IngestionCacheClient cache;
    private final Clock clock;
    private final FixtureSourceWorkflow workflow;
    private final FilesystemFixturePolicy filesystemPolicy;

    public SourceController(
            IngestionCacheClient cache,
            Clock clock,
            FixtureSourceWorkflow workflow,
            FilesystemFixturePolicy filesystemPolicy) {
        this.cache = cache;
        this.clock = clock;
        this.workflow = workflow;
        this.filesystemPolicy = filesystemPolicy;
    }

    @PostMapping
    public ResponseEntity<SourceResource> create(@RequestBody CreateSourceRequest request) {
        var context = TrustedRequestContext.requireIngestionContext();
        requireText(request.sourceId(), "source_id");
        requireText(request.connectorId(), "connector_id");
        requireText(request.connectorType(), "connector_type");
        requireText(request.sourceEndpoint(), "source_endpoint");
        if (!"file".equals(request.connectorType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "This fixture supports filesystem sources only");
        }
        try {
            filesystemPolicy.validateRoot(request.sourceEndpoint());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        SourceRow source = new SourceRow(
            context.tenantId(), request.sourceId(), request.connectorId(), request.connectorType(),
            request.sourceEndpoint(), request.capabilityFlags(), "ACTIVE", clock.instant(), null
        );
        try {
            SourceRow created = cache.createSourceIfAbsent(source);
            if (!created.connectorType().equals(source.connectorType())
                    || !created.sourceEndpoint().equals(source.sourceEndpoint())
                    || !created.capabilityFlags().equals(source.capabilityFlags())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "source_id already exists");
            }
            if (!"ACTIVE".equals(created.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "deleted source_id cannot be reused");
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(SourceResource.from(created));
        } catch (org.synanton.ingestioncache.domain.SourceIdentityConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "source_id already exists", e);
        }
    }

    @GetMapping("/{sourceId}")
    public SourceResource get(@PathVariable String sourceId) {
        return SourceResource.from(readSource(sourceId));
    }

    @GetMapping
    public List<SourceResource> list() {
        String tenantId = TrustedRequestContext.requireIngestionContext().tenantId();
        return cache.listSources(tenantId).stream().map(SourceResource::from).toList();
    }

    @PatchMapping("/{sourceId}")
    public SourceResource patch(@PathVariable String sourceId, @RequestBody PatchSourceRequest request) {
        SourceRow source = readSource(sourceId);
        if (!"ACTIVE".equals(source.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "deleted source configuration is immutable");
        }
        if (request.sourceEndpoint() != null) {
            requireText(request.sourceEndpoint(), "source_endpoint");
            try {
                filesystemPolicy.validateRoot(request.sourceEndpoint());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
            }
            if (!request.sourceEndpoint().equals(source.sourceEndpoint())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "a different filesystem origin requires a new source_id");
            }
        }
        String endpoint = request.sourceEndpoint() == null ? source.sourceEndpoint() : request.sourceEndpoint();
        Set<String> capabilityFlags = request.capabilityFlags() == null
            ? source.capabilityFlags() : request.capabilityFlags();
        cache.updateSourceConfiguration(source.tenantId(), sourceId, endpoint, capabilityFlags);
        return SourceResource.from(readSource(sourceId));
    }

    @DeleteMapping("/{sourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConfiguration(@PathVariable String sourceId) {
        SourceRow source = readSource(sourceId);
        cache.markSourceDeleted(source.tenantId(), sourceId);
    }

    @DeleteMapping("/{sourceId}/resources")
    public ResponseEntity<IngestionRunController.RunSubmission> deleteResource(
            @PathVariable String sourceId,
            @RequestParam("resource_id") String resourceId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        var context = TrustedRequestContext.requireIngestionContext();
        requireResourceId(resourceId);
        if (idempotencyKey != null && idempotencyKey.length() > 256) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key exceeds 256 characters");
        }
        RequestContext request = RequestContextHolder.get();
        String requestId = request == null || request.traceId() == null
            ? UUID.randomUUID().toString() : request.traceId();
        Submission submission = workflow.deleteResource(
            context, sourceId, resourceId, requestId, idempotencyKey);
        return ResponseEntity.accepted().body(IngestionRunController.RunSubmission.from(submission.run()));
    }

    @GetMapping("/{sourceId}/versions")
    public List<SourceVersionRow> listVersions(@PathVariable String sourceId) {
        SourceRow source = readSource(sourceId);
        return cache.listSourceIdentities(source.tenantId(), sourceId, source.connectorId()).stream()
            .flatMap(identity -> cache.listSourceVersions(
                identity.tenantId(), identity.connectorId(), identity.sourceId(), identity.resourceId()).stream())
            .sorted(Comparator.comparing(SourceVersionRow::observedAt))
            .toList();
    }

    @GetMapping("/{sourceId}/versions/{versionId}")
    public SourceVersionRow getVersion(@PathVariable String sourceId, @PathVariable UUID versionId) {
        SourceRow source = readSource(sourceId);
        for (SourceIdentityRow identity : cache.listSourceIdentities(
                source.tenantId(), sourceId, source.connectorId())) {
            var version = cache.readSourceVersion(
                identity.tenantId(), identity.connectorId(), identity.sourceId(), identity.resourceId(), versionId);
            if (version.isPresent()) {
                return version.get();
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "source version not found");
    }

    private SourceRow readSource(String sourceId) {
        String tenantId = TrustedRequestContext.requireIngestionContext().tenantId();
        return cache.readSource(tenantId, sourceId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "source not found"));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
    }

    private static void requireResourceId(String value) {
        requireText(value, "resource_id");
        if (value.startsWith("/") || value.contains("\\")
                || java.util.Arrays.stream(value.split("/", -1))
                    .anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "resource_id must be a normalized connector-relative path");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateSourceRequest(
        String sourceId,
        String connectorId,
        String connectorType,
        String sourceEndpoint,
        Set<String> capabilityFlags
    ) {
        public CreateSourceRequest {
            capabilityFlags = capabilityFlags == null ? Set.of() : Set.copyOf(capabilityFlags);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PatchSourceRequest(String sourceEndpoint, Set<String> capabilityFlags) {
        public PatchSourceRequest {
            capabilityFlags = capabilityFlags == null ? null : Set.copyOf(capabilityFlags);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SourceResource(
        String sourceId,
        String connectorId,
        String connectorType,
        String sourceEndpoint,
        Set<String> capabilityFlags,
        String status,
        java.time.Instant createdAt,
        java.time.Instant lastSuccessfulSyncAt
    ) {
        private static SourceResource from(SourceRow row) {
            return new SourceResource(row.sourceId(), row.connectorId(), row.connectorType(), row.sourceEndpoint(),
                row.capabilityFlags(), row.status(), row.createdAt(), row.lastSuccessfulSyncAt());
        }
    }
}
