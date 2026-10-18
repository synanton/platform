package org.synanton.synflux.lifecycle;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls durable non-terminal workflows so restart and bounded retry recovery need no client resubmission. */
@Component
public class WorkflowRecoveryScheduler {

    private final FixtureSourceWorkflow workflow;

    public WorkflowRecoveryScheduler(FixtureSourceWorkflow workflow) {
        this.workflow = workflow;
    }

    @Scheduled(
        initialDelayString = "${synflux.workflow.recovery-initial-delay-ms:1000}",
        fixedDelayString = "${synflux.workflow.recovery-poll-ms:5000}"
    )
    public void recoverDueWorkflows() {
        workflow.recoverPending();
    }
}
