package org.synanton.common.resolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GraphContractTest {

    @Test
    void identityKeepsTenantGraphAndProjectionDistinct() {
        GraphIdentity identity = GraphIdentity.canonical("tenant-a", "v1");

        assertThat(identity.tenantId()).isEqualTo("tenant-a");
        assertThat(identity.graphId()).isEqualTo("canonical");
        assertThat(identity.projectionVersion()).isEqualTo("v1");
        assertThatThrownBy(() -> new GraphIdentity(" ", "canonical", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphIdentity("tenant-a", " ", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphIdentity("tenant-a", "canonical", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void capabilitiesRequireTheSemanticsNotJustTheMethod() {
        assertThat(GraphCapabilities.NONE.supportsResolutionReconciliation()).isFalse();
        assertThat(GraphCapabilities.NONE.supportsRelationPromotion()).isFalse();

        GraphCapabilities noAtomic =
                new GraphCapabilities(true, false, true, true);
        assertThat(noAtomic.supportsResolutionReconciliation()).isTrue();
        assertThat(noAtomic.supportsRelationPromotion()).isFalse();

        GraphCapabilities noConditional =
                new GraphCapabilities(false, true, true, true);
        assertThat(noConditional.supportsResolutionReconciliation()).isFalse();
        assertThat(noConditional.supportsRelationPromotion()).isFalse();

        assertThat(GraphCapabilities.neo4j().supportsRelationPromotion()).isTrue();
    }
}
