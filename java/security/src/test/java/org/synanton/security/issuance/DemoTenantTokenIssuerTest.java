package org.synanton.security.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.synanton.storage.contract.service.ServiceAuthorizationException;
import org.synanton.storage.contract.service.ServiceCapability;
import org.synanton.storage.contract.service.ServicePrincipal;

class DemoTenantTokenIssuerTest {

    private static final String SECRET = "demo-issuer-test-secret-at-least-32-bytes";

    @Test
    void issuesShortLivedTenantTokenAndAuditsIt() throws Exception {
        List<IssuanceEvent> events = new ArrayList<>();
        DemoTenantTokenIssuer issuer = new DemoTenantTokenIssuer(
                SECRET, "synanton-demo", "synanton-local", 900, events::add);

        var issued = issuer.issue("svc-1", "tenant-a",
                Set.of("content.reconcile", "content.mutate"));

        SignedJWT jwt = SignedJWT.parse(issued.token());
        assertThat(jwt.verify(new MACVerifier(SECRET.getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getStringClaim("tenant_id")).isEqualTo("tenant-a");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("token_type")).isEqualTo("tenant_access");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("scope"))
                .isEqualTo("content.mutate content.reconcile");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("svc-1");
        assertThat(jwt.getJWTClaimsSet().getJWTID()).isEqualTo(issued.jti());
        assertThat(Duration.between(issued.issuedAt(), issued.expiresAt()).toSeconds()).isEqualTo(900);
        assertThat(issued.principal().capabilities()).containsExactlyInAnyOrder(
                ServiceCapability.CONTENT_RECONCILE_TENANT,
                ServiceCapability.CONTENT_MUTATE_TENANT);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().outcome()).isEqualTo("issued");
        assertThat(events.getFirst().tenantId()).isEqualTo("tenant-a");
    }

    @Test
    void unknownClaimFailsAtIssuanceBeforeAudit() {
        List<IssuanceEvent> events = new ArrayList<>();
        DemoTenantTokenIssuer issuer = new DemoTenantTokenIssuer(
                SECRET, "synanton-demo", "synanton-local", 900, events::add);

        assertThatThrownBy(() -> issuer.issue("svc-1", "tenant-a",
                Set.of("content.reconcile.typo")))
                .isInstanceOf(ServiceAuthorizationException.class)
                .hasMessageContaining("unknown capability claim");
        assertThat(events).isEmpty();
        assertThat(issuer.rejectedIssuances()).isEqualTo(1);
    }

    @Test
    void auditFailureAbortsIssuanceAndReturnsNoToken() {
        DemoTenantTokenIssuer issuer = new DemoTenantTokenIssuer(
                SECRET, "synanton-demo", "synanton-local", 900,
                event -> { throw new IllegalStateException("audit store down"); });

        assertThatThrownBy(() -> issuer.issue("svc-1", "tenant-a",
                Set.of("content.reconcile")))
                .isInstanceOf(IssuanceAuditException.class)
                .hasMessageContaining("token not issued");
    }

    @Test
    void deserializationCannotConstructCapabilityBearingPrincipal() {
        String json = "{\"principalId\":\"svc\",\"capabilities\":[\"CONTENT_RECONCILE_TENANT\"],"
                + "\"expiresAt\":\"2099-01-01T00:00:00Z\",\"issuer\":\"issuer\"}";
        assertThatThrownBy(() -> new ObjectMapper().readValue(json, ServicePrincipal.class))
                .as("no public/JSON creator; fromClaims is the only producer")
                .isInstanceOf(Exception.class);
    }

    @Test
    void rejectsTtlAboveDemoMaximum() {
        assertThatThrownBy(() -> new DemoTenantTokenIssuer(
                SECRET, "synanton-demo", "synanton-local", 901, event -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TTL");
    }
}
