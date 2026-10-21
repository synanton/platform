package org.synanton.synflux.lifecycle;

import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.domain.WorkflowStateRow;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FixtureSourceWorkflowIdentityTest {

    private static WorkflowStateRow workflow(String sourceId, String resourceId) {
        Instant now = Instant.now();
        return new WorkflowStateRow(
                "tenant-a", UUID.randomUUID(), "IngestionWorkflow", 1, UUID.randomUUID(), UUID.randomUUID(),
                sourceId, resourceId, "alice", "USER", "request-1", "key-1",
                "RECEIVED", "DISCOVER", now, now, null, null, "request-1", null, null, 0, null);
    }

    @Test
    void matchingIdentityReplaysTheExistingWorkflow() {
        assertDoesNotThrow(() ->
                FixtureSourceWorkflow.verifyRequestIdentity(workflow("source-a", "doc.pdf"), "source-a", "doc.pdf"));
    }

    @Test
    void differentSourceIsAConflict() {
        ResponseStatusException conflict = assertThrows(ResponseStatusException.class, () ->
                FixtureSourceWorkflow.verifyRequestIdentity(workflow("source-a", "doc.pdf"), "source-b", "doc.pdf"));
        assertEquals(409, conflict.getStatusCode().value());
    }

    @Test
    void differentResourceIsAConflict() {
        ResponseStatusException conflict = assertThrows(ResponseStatusException.class, () ->
                FixtureSourceWorkflow.verifyRequestIdentity(workflow("source-a", "doc.pdf"), "source-a", "other.pdf"));
        assertEquals(409, conflict.getStatusCode().value());
    }
}
