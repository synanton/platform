package org.synanton.annotations.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.synanton.annotations.domain.ProcessingRunService;
import org.synanton.annotations.domain.model.ProcessingRun;

import java.util.UUID;

@RestController
@RequestMapping("/processing-runs")
public class ProcessingRunController {

    private final ProcessingRunService processingRuns;

    public ProcessingRunController(ProcessingRunService processingRuns) {
        this.processingRuns = processingRuns;
    }

    @PostMapping
    public ResponseEntity<ProcessingRun> start(@RequestBody StartRunRequest body) {
        ProcessingRun run = processingRuns.start(
                body.producer(), body.producerVersion(), body.tenantId(),
                body.definitionId(), body.definitionVersion(), body.scope());
        return ResponseEntity.status(HttpStatus.CREATED).body(run);
    }

    @GetMapping("/{id}")
    public ProcessingRun get(@PathVariable("id") UUID id) {
        return processingRuns.get(id);
    }

    @PatchMapping("/{id}")
    public ProcessingRun complete(@PathVariable("id") UUID id, @RequestBody CompleteRunRequest body) {
        return processingRuns.complete(id, body.status(), body.errorSummary(), body.resourceConsumptionJson());
    }

    public record StartRunRequest(
            String producer, String producerVersion, String tenantId,
            String definitionId, Integer definitionVersion, String scope) {}

    public record CompleteRunRequest(String status, String errorSummary, String resourceConsumptionJson) {}
}
