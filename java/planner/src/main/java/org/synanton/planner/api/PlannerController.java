package org.synanton.planner.api;

import org.synanton.planner.domain.PlanRequest;
import org.synanton.planner.domain.PlanResult;
import org.synanton.planner.domain.PlannerStats;
import org.synanton.planner.service.EntityLabelIndex;
import org.synanton.planner.service.PlanService;
import org.springframework.web.bind.annotation.*;

@RestController
public class PlannerController {
    private final PlanService planService;
    private final EntityLabelIndex labelIndex;

    public PlannerController(PlanService planService, EntityLabelIndex labelIndex) {
        this.planService = planService;
        this.labelIndex = labelIndex;
    }

    @PostMapping("/plan")
    public PlanResult plan(@RequestBody PlanRequest req) {
        return planService.plan(req);
    }

    @PostMapping("/planner/refresh-labels")
    public void refreshLabels() {
        labelIndex.refresh();
    }

    @GetMapping("/planner/stats")
    public PlannerStats stats() {
        return planService.stats();
    }
}
