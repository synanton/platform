package org.synanton.annotations.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.synanton.annotations.domain.equalix.EqualixScheduler;
import org.synanton.annotations.domain.equalix.WorkloadClass;
import org.synanton.annotations.domain.resolutor.ChangeEvent;
import org.synanton.annotations.domain.resolutor.ChangeType;
import org.synanton.annotations.domain.resolutor.RecalculationPlan;
import org.synanton.annotations.domain.resolutor.ResolutorService;

@RestController
public class RecalculationController {

    private final ResolutorService resolutor;
    private final EqualixScheduler equalix;

    public RecalculationController(ResolutorService resolutor, EqualixScheduler equalix) {
        this.resolutor = resolutor;
        this.equalix = equalix;
    }

    /**
     * Resolves the impact of an annotation definition version publish and hands the
     * resulting plan to Equalix under the caller-supplied workload class (design §49-§50).
     */
    @PostMapping("/recalculate")
    public ResponseEntity<RecalculationPlan> recalculate(@RequestBody RecalculateRequest body) {
        ChangeEvent event = new ChangeEvent(
                ChangeType.ANNOTATION_DEFINITION_VERSION_PUBLISHED, body.definitionId(), body.fromVersion(), body.toVersion());
        RecalculationPlan plan = resolutor.resolve(event, body.tenantId());
        equalix.submit(plan, body.workloadClass(), "resolutor-api", "1.0", body.tenantId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(plan);
    }

    public record RecalculateRequest(
            String tenantId, String definitionId, Integer fromVersion, Integer toVersion, WorkloadClass workloadClass) {}
}
