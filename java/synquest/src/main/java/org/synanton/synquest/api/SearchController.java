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
        String tenant = req.tenant() != null ? req.tenant()
                : (String) httpReq.getAttribute("tenant");
        SearchRequest effective = req.tenant() != null ? req
                : new SearchRequest(tenant, req.query(), req.topK(), req.topKDense(), req.topKLexical(), req.rrfK());
        return ResponseEntity.ok(searchService.search(effective));
    }

    @PostMapping("/reindex")
    public ResponseEntity<Map<String, String>> reindex(
            @RequestParam(defaultValue = "demo") String tenant) throws IOException {
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
            @RequestParam(defaultValue = "demo") String tenant,
            HttpServletRequest httpReq) throws IOException {
        String effectiveTenant = (String) httpReq.getAttribute("tenant");
        if (effectiveTenant == null) effectiveTenant = tenant;
        return ResponseEntity.ok(searchService.stats(effectiveTenant));
    }
}
