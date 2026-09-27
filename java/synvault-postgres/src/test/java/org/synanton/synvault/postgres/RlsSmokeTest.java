package org.synanton.synvault.postgres;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG-POC-003 RLS smoke test. Tenant scoping uses {@code SET LOCAL
 * app.tenant_id} inside an explicit transaction (never session-level state, so
 * pooled-connection reuse cannot leak a tenant — see PG-POC-003 review note).
 * {@code FORCE ROW LEVEL SECURITY} in the canonical DDL closes the
 * owner-bypass hole for PoC/test roles.
 */
class RlsSmokeTest extends PostgresTestBase {

    @BeforeAll
    static void startInfra() {
        ensureStarted();
    }

    private static void setTenant(Statement stmt, UUID tenant) throws Exception {
        stmt.execute("SET LOCAL app.tenant_id = '" + tenant + "'");
    }

    private static int countDocuments(Connection conn) throws Exception {
        try (ResultSet rs = conn.createStatement().executeQuery("SELECT count(*) FROM documents")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void tenantSeesOnlyOwnRows() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();

        try (Connection conn = connection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                setTenant(stmt, tenantA);
                stmt.execute(
                        "INSERT INTO documents (tenant_id, doc_id, title, storage_revision,"
                                + " created_at, updated_at) VALUES ('"
                                + tenantA + "', '" + docA + "', 'doc-a', 1, now(), now())");
            }
            conn.commit();

            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                setTenant(stmt, tenantB);
                stmt.execute(
                        "INSERT INTO documents (tenant_id, doc_id, title, storage_revision,"
                                + " created_at, updated_at) VALUES ('"
                                + tenantB + "', '" + docB + "', 'doc-b', 1, now(), now())");
            }
            conn.commit();
        }

        // As A: exactly one row (own).
        try (Connection conn = connection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                setTenant(stmt, tenantA);
                assertThat(countDocuments(conn)).as("tenant A sees own row").isEqualTo(1);
                try (ResultSet rs =
                        stmt.executeQuery("SELECT doc_id FROM documents")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo(docA.toString());
                    assertThat(rs.next()).as("no second row leaks to A").isFalse();
                }
            }
            conn.rollback();
        }

        // As B: exactly one row (own), and it is docB, not docA.
        try (Connection conn = connection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                setTenant(stmt, tenantB);
                assertThat(countDocuments(conn)).as("tenant B sees own row").isEqualTo(1);
                try (ResultSet rs = stmt.executeQuery("SELECT doc_id FROM documents")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo(docB.toString());
                }
            }
            conn.rollback();
        }
    }

    @Test
    void unsetTenantSeesNothing() throws Exception {
        UUID tenant = UUID.randomUUID();
        try (Connection conn = connection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                setTenant(stmt, tenant);
                stmt.execute(
                        "INSERT INTO documents (tenant_id, doc_id, title, storage_revision,"
                                + " created_at, updated_at) VALUES ('"
                                + tenant + "', '" + UUID.randomUUID() + "', 'x', 1, now(), now())");
            }
            conn.commit();
        }

        // No SET LOCAL at all: current_setting(..., true) is NULL, matches no
        // rows — fail-closed, never fail-open.
        try (Connection conn = connection()) {
            conn.setAutoCommit(false);
            assertThat(countDocuments(conn)).as("unset tenant sees nothing").isZero();
            conn.rollback();
        }
    }

    @Test
    void crossTenantWriteAffectsNothing() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID docA = UUID.randomUUID();
        try (Connection conn = connection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                setTenant(stmt, tenantA);
                stmt.execute(
                        "INSERT INTO documents (tenant_id, doc_id, title, storage_revision,"
                                + " created_at, updated_at) VALUES ('"
                                + tenantA + "', '" + docA + "', 'victim', 1, now(), now())");
            }
            conn.commit();
        }

        try (Connection conn = connection()) {
            conn.setAutoCommit(false);
            int deleted;
            int updated;
            try (Statement stmt = conn.createStatement()) {
                setTenant(stmt, tenantB);
                deleted = stmt.executeUpdate("DELETE FROM documents");
                updated = stmt.executeUpdate("UPDATE documents SET title = 'hijacked'");
                assertThat(countDocuments(conn)).as("B sees none of A's rows").isZero();
            }
            conn.rollback();
            assertThat(deleted).as("cross-tenant delete affects nothing").isZero();
            assertThat(updated).as("cross-tenant update affects nothing").isZero();
        }
    }
}
