package org.synanton.synquest.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.synanton.common.resolution.ExactTypeCompatibility;
import org.synanton.common.resolution.ResolutionCandidate;
import org.synanton.common.resolution.ResolutionContext;
import org.synanton.common.resolution.ResolutionDecision;
import org.synanton.common.resolution.ResolutionOutcome;

class DefaultResolutionPolicyTest {

    private final DefaultResolutionPolicy policy = new DefaultResolutionPolicy(
            "v-test", 0.02, 0.005, new ExactTypeCompatibility());

    @Test
    void noCandidatesIsUnresolved() {
        ResolutionDecision decision = policy.decide(context(List.of()));
        assertThat(decision.outcome()).isEqualTo(ResolutionOutcome.UNRESOLVED);
        assertThat(decision.reasonCode()).isEqualTo(DefaultResolutionPolicy.NO_CANDIDATES);
    }

    @Test
    void strongCompatibleCandidateWithMarginResolves() {
        ResolutionDecision decision = policy.decide(context(List.of(
                candidate("e1", "MaintenanceSystem", 0.033, "label"),
                candidate("e2", "MaintenanceSystem", 0.016, "text"))));
        assertThat(decision.outcome()).isEqualTo(ResolutionOutcome.RESOLVED);
        assertThat(decision.resolvedEntityId()).isEqualTo("e1");
        assertThat(decision.reasonCode()).isEqualTo(DefaultResolutionPolicy.RESOLVED_LABEL);
        assertThat(decision.strategyVersion()).isEqualTo("v-test");
    }

    @Test
    void lowMarginIsAmbiguousEvenWhenStrong() {
        ResolutionDecision decision = policy.decide(context(List.of(
                candidate("e1", "MaintenanceSystem", 0.033, "label"),
                candidate("e2", "MaintenanceSystem", 0.032, "label"))));
        assertThat(decision.outcome()).isEqualTo(ResolutionOutcome.AMBIGUOUS);
        assertThat(decision.reasonCode()).isEqualTo(DefaultResolutionPolicy.LOW_MARGIN_AMBIGUOUS);
    }

    @Test
    void weakCandidateCannotResolveOnMarginAlone() {
        ResolutionDecision decision = policy.decide(context(List.of(
                candidate("e1", "MaintenanceSystem", 0.01, "text"))));
        assertThat(decision.outcome()).isEqualTo(ResolutionOutcome.UNRESOLVED);
        assertThat(decision.reasonCode()).isEqualTo(DefaultResolutionPolicy.BELOW_ABSOLUTE_THRESHOLD);
    }

    @Test
    void incompatibleTypeNeverResolves() {
        ResolutionDecision decision = policy.decide(context(List.of(
                candidate("e1", "Organization", 0.033, "label"))));
        assertThat(decision.outcome()).isEqualTo(ResolutionOutcome.UNRESOLVED);
        assertThat(decision.reasonCode()).isEqualTo(DefaultResolutionPolicy.TYPE_INCOMPATIBLE);
    }

    @Test
    void unknownProposedTypeRequiresReview() {
        ResolutionDecision decision = policy.decide(new ResolutionContext(
                "t", "v", "m", "mention", "UNKNOWN", List.of(
                candidate("e1", "Organization", 0.033, "label")), "1.0.0"));
        assertThat(decision.outcome()).isEqualTo(ResolutionOutcome.REVIEW_REQUIRED);
        assertThat(decision.reasonCode()).isEqualTo(DefaultResolutionPolicy.UNKNOWN_PROPOSED_TYPE);
    }

    @Test
    void thresholdsAreInclusiveAndTiesAreDeterministic() {
        ResolutionDecision equality = policy.decide(context(List.of(
                candidate("e1", "MaintenanceSystem", 0.02, "label"))));
        assertThat(equality.outcome()).isEqualTo(ResolutionOutcome.RESOLVED);

        ResolutionDecision tie = policy.decide(context(List.of(
                candidate("e2", "MaintenanceSystem", 0.03, "label"),
                candidate("e1", "MaintenanceSystem", 0.03, "label"))));
        assertThat(tie.outcome()).isEqualTo(ResolutionOutcome.AMBIGUOUS);
        assertThat(tie.diagnostics().get("top_entity_id")).isEqualTo("e1");
    }

    private static ResolutionContext context(List<ResolutionCandidate> candidates) {
        return new ResolutionContext("t", "v", "m", "mention", "MaintenanceSystem", candidates, "1.0.0");
    }

    private static ResolutionCandidate candidate(String id, String type, double score, String field) {
        return new ResolutionCandidate(id, id, type, score, field, "RRF");
    }
}
