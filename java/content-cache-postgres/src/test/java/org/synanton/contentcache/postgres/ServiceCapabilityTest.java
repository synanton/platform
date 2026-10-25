package org.synanton.contentcache.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.synanton.contentcache.ContentMetadata;
import org.synanton.contentcache.ContentReference;
import org.synanton.contentcache.PutResult;
import org.synanton.contentcache.service.CrossTenantContentReconciler;
import org.synanton.contentcache.service.CrossTenantRequest;
import org.synanton.contentcache.service.PartialReconcileException;
import org.synanton.contentcache.service.ReconcileReport;
import org.synanton.contentcache.service.ServiceAuditMetrics;
import org.synanton.contentcache.service.ServiceAuthorizationException;
import org.synanton.contentcache.service.ServiceCapability;
import org.synanton.contentcache.service.ServiceContentStore;
import org.synanton.contentcache.service.ServicePrincipal;
import org.synanton.storage.contract.TenantScope;

/** #339 service-capability implementation contract. */
class ServiceCapabilityTest extends PostgresTestBase {

    private static final String ACTION = ServiceCapability.CONTENT_RECONCILE_CROSS_TENANT.name();
    private static DataSource dataSource;
    private CrossTenantContentReconciler reconciler;

    @BeforeAll
    static void start() throws Exception {
        ensureStarted();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl());
        ds.setUser("app");
        ds.setPassword("app");
        dataSource = ds;
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection admin = adminConnection();
                var stmt = admin.createStatement()) {
            stmt.execute("TRUNCATE audit_log, reference_registry, retention_policies, artifacts");
        }
        reconciler = new CrossTenantContentReconciler(dataSource, 90);
    }

    private static TenantScope scope(String prefix) {
        return TenantScope.of(prefix + "-" + UUID.randomUUID());
    }

    private static TenantScopedContentStore store(TenantScope scope) {
        return TenantScopedContentStore.forTenant(dataSource, 90, scope);
    }

    private static ServicePrincipal principal(String id, ServiceCapability... capabilities) {
        return new ServicePrincipal(id, Set.of(capabilities), Instant.now().plusSeconds(300), "test");
    }

    private static CrossTenantRequest request(
            ServicePrincipal principal, String traceId, TenantScope... scopes) {
        return request(principal, "op-" + traceId, traceId, scopes);
    }

    private static CrossTenantRequest request(
            ServicePrincipal principal, String operationId, String traceId, TenantScope... scopes) {
        return new CrossTenantRequest(
                principal, new LinkedHashSet<>(java.util.List.of(scopes)),
                "reconcile", "test-reason", operationId, traceId);
    }

    private static String put(TenantScopedContentStore store, String bytes) {
        PutResult result = store.put(
                bytes.getBytes(StandardCharsets.UTF_8),
                ContentMetadata.of("text/plain", "owner-1"));
        return result.reference().digest();
    }

    private static int auditRows(TenantScope scope, String outcome) throws Exception {
        return auditRows(scope, ACTION, outcome);
    }

    private static int auditRows(TenantScope scope, String action, String outcome) throws Exception {
        try (Connection con = adminConnection();
                var ps = con.prepareStatement(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?"
                                + " AND after_state LIKE ?")) {
            ps.setString(1, scope.tenantId());
            ps.setString(2, action);
            ps.setString(3, "%\"outcome\":\"" + outcome + "\"%");
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void capabilityPresentTenantSetA_readsAOnly_auditExactlyA() throws Exception {
        TenantScope a = scope("a");
        TenantScope b = scope("b");
        TenantScopedContentStore storeA = store(a);
        TenantScopedContentStore storeB = store(b);
        String digest = put(storeA, "shared");
        put(storeB, "shared");

        ReconcileReport report = reconciler.reconcile(
                request(principal("svc", ServiceCapability.CONTENT_RECONCILE_CROSS_TENANT),
                        "trace-a", a),
                digest);

        assertThat(report.count(a)).isEqualTo(1);
        assertThat(report.count(b)).isZero();
        assertThat(auditRows(a, "allowed")).isEqualTo(1);
        assertThat(auditRows(b, "allowed")).isZero();
    }

    @Test
    void capabilityAbsentTenantSetA_returnsZeroNotError() throws Exception {
        TenantScope a = scope("a");
        String digest = put(store(a), "shared");

        ReconcileReport report = reconciler.reconcile(
                request(principal("svc-no-cap"), "trace-no-cap", a),
                digest);

        assertThat(report.count(a)).isZero();
        assertThat(auditRows(a, "allowed")).isZero();
        assertThat(auditRows(a, "denied")).isEqualTo(1);
    }

    @Test
    void multiTenantPositive_readsBothAndAuditsEach() throws Exception {
        TenantScope a = scope("a");
        TenantScope b = scope("b");
        String digest = put(store(a), "shared");
        put(store(b), "shared");

        ReconcileReport report = reconciler.reconcile(
                request(principal("svc", ServiceCapability.CONTENT_RECONCILE_CROSS_TENANT),
                        "trace-ab", a, b),
                digest);

        assertThat(report.count(a)).isEqualTo(1);
        assertThat(report.count(b)).isEqualTo(1);
        assertThat(auditRows(a, "allowed")).isEqualTo(1);
        assertThat(auditRows(b, "allowed")).isEqualTo(1);
    }

    @Test
    void crossTenantReadUsesTheSamePerTenantReadPath() throws Exception {
        TenantScope a = scope("a");
        TenantScopedContentStore storeA = store(a);
        String digest = put(storeA, "shared");

        int direct = storeA.countByDigest(digest);
        ReconcileReport report = reconciler.reconcile(
                request(principal("svc", ServiceCapability.CONTENT_RECONCILE_CROSS_TENANT),
                        "trace-parity", a),
                digest);

        assertThat(report.count(a)).isEqualTo(direct);
    }

    @Test
    void wildcardAndOversizeTenantSetsAreRejected() {
        ServicePrincipal p = principal("svc", ServiceCapability.CONTENT_RECONCILE_CROSS_TENANT);
        assertThatThrownBy(() -> request(p, "trace-empty"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(p, "trace-wildcard", TenantScope.of("*")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wildcard");
        Set<TenantScope> tooMany = new LinkedHashSet<>();
        for (int i = 0; i < CrossTenantRequest.MAX_CROSS_TENANT_SCOPE + 1; i++) {
            tooMany.add(scope("bulk"));
        }
        assertThatThrownBy(() -> new CrossTenantRequest(p, tooMany, "reconcile", "r", "op", "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void claimMappingIsClosedAndFailsAtIssuance() {
        ServicePrincipal mapped = ServicePrincipal.fromClaims(
                "svc-claims",
                Set.of("content.reconcile", "content.mutate"),
                Instant.now().plusSeconds(300),
                "issuer");
        assertThat(mapped.capabilities()).containsExactlyInAnyOrder(
                ServiceCapability.CONTENT_RECONCILE_TENANT,
                ServiceCapability.CONTENT_MUTATE_TENANT);
        assertThat(ServicePrincipal.fromClaims(
                "svc-empty", Set.of(), Instant.now().plusSeconds(300), "issuer").capabilities())
                .isEmpty();

        assertThatThrownBy(() -> ServicePrincipal.fromClaims(
                "svc-bad", Set.of("content.reconcile.typo"),
                Instant.now().plusSeconds(300), "issuer"))
                .as("unknown claim must fail at issuance, not become zero rows")
                .isInstanceOf(ServiceAuthorizationException.class)
                .hasMessageContaining("unknown capability claim");
    }

    @Test
    void mutationWithoutCapabilityFailsBeforeAnyWrite() throws Exception {
        TenantScope a = scope("a");
        TenantScopedContentStore storeA = store(a);
        ContentReference ref = storeA.put(
                "mutate".getBytes(StandardCharsets.UTF_8),
                ContentMetadata.of("text/plain", "owner-1")).reference();
        ServiceContentStore noCap = new ServiceContentStore(storeA, principal("svc-read"));

        assertThatThrownBy(() -> noCap.setRetention(ref, 30))
                .isInstanceOf(ServiceAuthorizationException.class);
        assertThat(retentionDays(a, ref)).isEqualTo(90);
        assertThat(auditRows(a, ServiceCapability.CONTENT_MUTATE_TENANT.name(), "denied"))
                .isEqualTo(1);

        ServiceContentStore canMutate = new ServiceContentStore(
                storeA, principal("svc-write", ServiceCapability.CONTENT_MUTATE_TENANT));
        canMutate.setRetention(ref, 30);
        assertThat(retentionDays(a, ref)).isEqualTo(30);
    }

    @Test
    void tenantAttributionStaysPerScope() throws Exception {
        TenantScope a = scope("a");
        TenantScope b = scope("b");
        String digestA = put(store(a), "a-only");
        put(store(b), "b-only");

        ReconcileReport report = reconciler.reconcile(
                request(principal("svc", ServiceCapability.CONTENT_RECONCILE_CROSS_TENANT),
                        "trace-attribution", a, b),
                digestA);

        assertThat(report.count(a)).isEqualTo(1);
        assertThat(report.count(b)).as("B must not inherit A's row").isZero();
    }

    @Test
    void partialFailureCarriesPerTenantOutcomesAndRetryDeduplicatesAudit() throws Exception {
        TenantScope a = scope("a");
        TenantScope b = scope("b");
        String digest = put(store(a), "shared");
        put(store(b), "shared");
        ServicePrincipal p = principal("partial-svc",
                ServiceCapability.CONTENT_RECONCILE_CROSS_TENANT);
        CrossTenantRequest req = request(p, "op-partial", "trace-partial", a, b);
        installAuditFailure("partial-svc", b.tenantId());
        try {
            assertThatThrownBy(() -> reconciler.reconcile(req, digest))
                    .isInstanceOfSatisfying(PartialReconcileException.class, failure -> {
                        assertThat(failure.outcomes().get(a).success()).isTrue();
                        assertThat(failure.outcomes().get(b).success()).isFalse();
                    });
            assertThat(auditRows(a, "allowed")).isEqualTo(1);
            assertThat(auditRows(b, "allowed")).isZero();
        } finally {
            removeAuditFailure();
        }

        ReconcileReport retried = reconciler.reconcile(req, digest);
        assertThat(retried.count(a)).isEqualTo(1);
        assertThat(retried.count(b)).isEqualTo(1);
        assertThat(auditRows(a, "allowed")).as("retry must not duplicate A audit").isEqualTo(1);
        assertThat(auditRows(b, "allowed")).isEqualTo(1);
    }

    @Test
    void mutationDenialAuditFailureCountsTheLoss() throws Exception {
        TenantScope a = scope("a");
        TenantScopedContentStore storeA = store(a);
        ContentReference ref = storeA.put(
                "mutate".getBytes(StandardCharsets.UTF_8),
                ContentMetadata.of("text/plain", "owner-1")).reference();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ServiceContentStore noCap = new ServiceContentStore(
                storeA, principal("mutation-audit-fail"),
                new ServiceAuditMetrics(registry));
        installAuditFailure("mutation-audit-fail");
        try {
            assertThatThrownBy(() -> noCap.setRetention(ref, 30))
                    .isInstanceOf(ServiceAuthorizationException.class);
            assertThat(noCap.denialAuditFailures()).isEqualTo(1);
            assertThat(registry.get(ServiceAuditMetrics.DENIAL_WRITE_FAILURE).counter().count())
                    .isEqualTo(1.0);
        } finally {
            removeAuditFailure();
        }
    }

    @Test
    void auditFailureAbortsAllowedReadAndCarriesOutcome() throws Exception {
        TenantScope a = scope("a");
        String digest = put(store(a), "shared");
        installAuditFailure("audit-fail");
        try {
            assertThatThrownBy(() -> reconciler.reconcile(
                    request(principal("audit-fail", ServiceCapability.CONTENT_RECONCILE_CROSS_TENANT),
                            "trace-audit-fail", a),
                    digest))
                    .isInstanceOfSatisfying(PartialReconcileException.class, failure ->
                            assertThat(failure.outcomes().get(a).success()).isFalse());
            assertThat(auditRows(a, "allowed")).isZero();
        } finally {
            removeAuditFailure();
        }
    }

    @Test
    void denialAuditFailureStillReturnsZeroRowsAndCountsTheLoss() throws Exception {
        TenantScope a = scope("a");
        String digest = put(store(a), "shared");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CrossTenantContentReconciler metricReconciler = new CrossTenantContentReconciler(
                dataSource, 90, new ServiceAuditMetrics(registry));
        installAuditFailure("deny-audit-fail");
        try {
            ReconcileReport report = metricReconciler.reconcile(
                    request(principal("deny-audit-fail"), "trace-deny-audit-fail", a),
                    digest);
            assertThat(report.count(a)).isZero();
            assertThat(metricReconciler.denialAuditFailures()).isEqualTo(1);
            assertThat(registry.get(ServiceAuditMetrics.DENIAL_WRITE_FAILURE).counter().count())
                    .isEqualTo(1.0);
        } finally {
            removeAuditFailure();
        }
    }

    @Test
    void expiredPrincipalReturnsZeroRowsNotError() {
        TenantScope a = scope("a");
        ServicePrincipal expired = new ServicePrincipal(
                "svc-expired",
                Set.of(ServiceCapability.CONTENT_RECONCILE_CROSS_TENANT),
                Instant.now().minusSeconds(1),
                "test");
        ReconcileReport report = reconciler.reconcile(request(expired, "trace-expired", a), "sha256:x");
        assertThat(report.count(a)).isZero();
    }

    private static int retentionDays(TenantScope scope, ContentReference ref) throws Exception {
        try (Connection con = adminConnection();
                var ps = con.prepareStatement(
                        "SELECT retention_days FROM retention_policies"
                                + " WHERE tenant_id = ? AND artifact_id = ?::uuid LIMIT 1")) {
            ps.setString(1, scope.tenantId());
            ps.setString(2, ref.artifactId());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void installAuditFailure(String actor) throws Exception {
        installAuditFailure(actor, null);
    }

    private static void installAuditFailure(String actor, String tenantId) throws Exception {
        String tenantPredicate = tenantId == null
                ? ""
                : " AND NEW.tenant_id = '" + tenantId + "'";
        try (Connection con = adminConnection(); var stmt = con.createStatement()) {
            stmt.execute("CREATE OR REPLACE FUNCTION test_fail_audit() RETURNS trigger AS $$"
                    + " BEGIN IF NEW.actor = '" + actor + "'" + tenantPredicate + " THEN"
                    + " RAISE EXCEPTION 'audit down'; END IF; RETURN NEW; END;"
                    + " $$ LANGUAGE plpgsql");
            stmt.execute("CREATE TRIGGER test_fail_audit BEFORE INSERT ON audit_log"
                    + " FOR EACH ROW EXECUTE FUNCTION test_fail_audit()");
        }
    }

    private static void removeAuditFailure() throws Exception {
        try (Connection con = adminConnection(); var stmt = con.createStatement()) {
            stmt.execute("DROP TRIGGER IF EXISTS test_fail_audit ON audit_log");
            stmt.execute("DROP FUNCTION IF EXISTS test_fail_audit()");
        }
    }
}
