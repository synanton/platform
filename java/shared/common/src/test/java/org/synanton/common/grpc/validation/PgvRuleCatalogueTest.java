package org.synanton.common.grpc.validation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.synanton.common.grpc.validation.PgvViolationAssertions.assertRejectedField;

class PgvRuleCatalogueTest {

    private final PgvRuleCatalogue catalogue = new PgvRuleCatalogue();

    @Test
    void shouldAcceptValidGrant() {
        Map<String, String> grant = Map.of(
                "tenant_id", "demo",
                "subject_id", "user:alice",
                "subject_type", "USER",
                "resource_id", "11111111-1111-1111-1111-111111111111",
                "resource_type", "DOCUMENT",
                "permission", "READ",
                "idempotency_key", "key-1"
        );
        assertThat(catalogue.validateGrant(grant)).isEmpty();
    }

    @Test
    void shouldRejectInvalidResourceType() {
        Map<String, String> grant = Map.of(
                "tenant_id", "demo",
                "subject_id", "user:alice",
                "subject_type", "USER",
                "resource_id", "11111111-1111-1111-1111-111111111111",
                "resource_type", "WIDGET",
                "permission", "READ",
                "idempotency_key", "key-1"
        );
        assertRejectedField(catalogue.validateGrant(grant), "resource_type", "string.in");
    }

    @Test
    void shouldAcceptValidClassGrant() {
        Map<String, String> grant = Map.of(
                "tenant_id", "demo",
                "subject_id", "hr",
                "subject_type", "GROUP",
                "class", "PERSONAL",
                "permission", "SEARCH",
                "idempotency_key", "key-1"
        );
        assertThat(catalogue.validateClassGrant(grant)).isEmpty();
    }

    @Test
    void shouldRejectInvalidClassGrantPermission() {
        Map<String, String> grant = Map.of(
                "tenant_id", "demo",
                "subject_id", "hr",
                "subject_type", "GROUP",
                "class", "PERSONAL",
                "permission", "ADMIN",
                "idempotency_key", "key-1"
        );
        assertRejectedField(catalogue.validateClassGrant(grant), "permission", "string.in");
    }
}
