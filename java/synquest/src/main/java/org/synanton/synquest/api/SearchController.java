package org.synanton.synquest.api;

import jakarta.servlet.http.HttpServletRequest;
import org.synanton.synquest.api.dto.*;
import org.synanton.synquest.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);
    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping("/search")
    public ResponseEntity<SearchResponse> search(
            @RequestBody SearchRequest req,
            HttpServletRequest httpReq) throws IOException {
        if (searchService.getStatus() == SearchService.Status.STARTING) {
            return ResponseEntity.status(503).build();
        }
        String tenant = req.tenant();
        if (tenant == null || tenant.isBlank()) {
            throw new org.synanton.common.tenant.MissingTenantContextException(
                    "legacy /search requires an explicit tenant; /v1/search derives it from the verified identity");
        }
        return ResponseEntity.ok(searchService.search(req));
    }

    @PostMapping("/reindex")
    public ResponseEntity<Map<String, String>> reindex(
            @RequestParam(name = "tenant") String tenant) throws IOException {
        if (tenant.isBlank()) {
            throw new org.synanton.common.tenant.MissingTenantContextException();
        }
        searchService.reindex(tenant);
        return ResponseEntity.ok(Map.of("status", "done", "tenant", tenant));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        String status = searchService.getStatus().name().toLowerCase();
        int httpStatus = searchService.getStatus() == SearchService.Status.READY ? 200 : 503;
        return ResponseEntity.status(httpStatus).body(Map.of("status", status));
    }

    @GetMapping("/index/stats")
    public ResponseEntity<IndexStats> stats(
            @RequestParam(name = "tenant") String tenant,
            HttpServletRequest httpReq) throws IOException {
        if (tenant.isBlank()) {
            throw new org.synanton.common.tenant.MissingTenantContextException();
        }
        return ResponseEntity.ok(searchService.stats(tenant));
    }
}
