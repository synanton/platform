package org.synanton.synflux.api;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.ExtractionEngineAdapter;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.PreEntity;
import org.synanton.extraction.engine.RelationCandidate;
import org.synanton.extraction.engine.TextSegment;
import org.synanton.synflux.config.ExtractionEngineProperties;
import org.synanton.synflux.security.TrustedRequestContext;

/**
 * RAKG preview surface: send raw text and see exactly what the selected
 * extraction engine returns, without waiting for a full document ingestion.
 * Tenant context is verified by the shared JWT filter; the endpoint is a
 * diagnostic/test seam, not a persistence path.
 */
@RestController
@RequestMapping("/api/v1/extraction")
public class ExtractionPreviewController {

    static final int MAX_TEXT_CHARS = 20_000;

    private final ExtractionEngineAdapter adapter;
    private final ExtractionEngineProperties properties;

    public ExtractionPreviewController(ExtractionEngineAdapter adapter,
                                       ExtractionEngineProperties properties) {
        this.adapter = adapter;
        this.properties = properties;
    }

    @PostMapping("/preview")
    public PreviewResponse preview(@RequestBody PreviewRequest request) {
        String tenant = TrustedRequestContext.requireIngestionContext().tenantId();
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text is required");
        }
        String text = request.text();
        if (text.length() > MAX_TEXT_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "text exceeds " + MAX_TEXT_CHARS + " characters");
        }

        ExtractionResult result;
        try {
            result = adapter.extract(
                    new TextSegment("preview-" + UUID.randomUUID(), text, 0),
                    new ExtractionContext(tenant, "preview", Set.of()));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "extraction engine failed: " + e.getMessage());
        }

        String engine = properties.provider() == null
                ? "regex"
                : properties.provider().strip().toLowerCase(Locale.ROOT);
        String model = "llm".equals(engine)
                ? (properties.model() == null ? "qwen3-extraction" : properties.model())
                : null;
        return new PreviewResponse(engine, model, result.preEntities(), result.relations());
    }

    public record PreviewRequest(String text) {}

    public record PreviewResponse(
            String engine,
            String model,
            List<PreEntity> preEntities,
            List<RelationCandidate> relations) {}
}
