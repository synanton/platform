package org.synanton.relix.graph;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class EntityCanonicalizerTest {

    private final EntityCanonicalizer canonicalizer = new EntityCanonicalizer();

    @Test
    void sameInputProducesSameId() {
        UUID id1 = canonicalizer.entityId("demo", "Organization", "Acme Corp");
        UUID id2 = canonicalizer.entityId("demo", "Organization", "Acme Corp");
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void normalisationCollapsesCaseAndSpaces() {
        UUID id1 = canonicalizer.entityId("demo", "Organization", "Acme Corp");
        UUID id2 = canonicalizer.entityId("demo", "Organization", "ACME  CORP");
        UUID id3 = canonicalizer.entityId("demo", "Organization", "acme corp");
        assertThat(id1).isEqualTo(id2).isEqualTo(id3);
    }

    @Test
    void trailingPunctuationStripped() {
        UUID id1 = canonicalizer.entityId("demo", "Organization", "Acme Corp");
        UUID id2 = canonicalizer.entityId("demo", "Organization", "Acme Corp.");
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void differentTypesDifferentIds() {
        UUID orgId = canonicalizer.entityId("demo", "Organization", "Acme Corp");
        UUID personId = canonicalizer.entityId("demo", "Person", "Acme Corp");
        assertThat(orgId).isNotEqualTo(personId);
    }

    @Test
    void differentTenantsDifferentIds() {
        UUID id1 = canonicalizer.entityId("tenant-a", "Organization", "Acme Corp");
        UUID id2 = canonicalizer.entityId("tenant-b", "Organization", "Acme Corp");
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void edgeIdIsDeterministic() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        UUID e1 = canonicalizer.edgeId("demo", from, "supplies_to", to);
        UUID e2 = canonicalizer.edgeId("demo", from, "supplies_to", to);
        assertThat(e1).isEqualTo(e2);
    }

    @Test
    void edgeIdDiffersOnVerbChange() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        UUID e1 = canonicalizer.edgeId("demo", from, "supplies_to", to);
        UUID e2 = canonicalizer.edgeId("demo", from, "acquired", to);
        assertThat(e1).isNotEqualTo(e2);
    }

    @Test
    void uuidV5Version() {
        UUID id = canonicalizer.entityId("demo", "Org", "Test");
        assertThat(id.version()).isEqualTo(5);
    }
}
