package org.synanton.synvault.ydb;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.UUID;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.table.Session;
import tech.ydb.table.TableClient;

/**
 * Live-YDB fixture (021): shared transport/client per JVM, one table set per test
 * class (random prefix — no DDL churn, parallel-safe). Row isolation additionally
 * uses per-store tenant namespaces. CA handling mirrors the connectivity test.
 */
abstract class YdbTestBase {

    private static volatile GrpcTransport transport;
    private static volatile TableClient tableClient;

    protected static synchronized void ensureStarted() {
        if (tableClient != null) {
            return;
        }
        try {
            String caPath = firstExisting(
                    System.getenv().getOrDefault("YDB_CA_PATH", ""),
                    System.getProperty("ydb.ca.path", ""),
                    "/tmp/ydb-ca.pem",
                    System.getProperty("user.home") + "/.config/ydb-ca.pem");
            byte[] ca = Files.readAllBytes(Paths.get(caPath));
            transport =
                    GrpcTransport.forConnectionString("grpcs://localhost:2135/local")
                            .withSecureConnection(ca)
                            .build();
            tableClient = TableClient.newClient(transport).build();
        } catch (Exception e) {
            throw new IllegalStateException("YDB test fixture failed to start", e);
        }
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    if (tableClient != null) {
                                        tableClient.close();
                                    }
                                    if (transport != null) {
                                        transport.close();
                                    }
                                }));
    }

    protected static TableClient client() {
        ensureStarted();
        return tableClient;
    }

    protected static Session session() {
        return client()
                .createSession(Duration.ofSeconds(10))
                .join()
                .getValue();
    }

    protected static void scheme(String yql) {
        try (Session session = session()) {
            tech.ydb.core.Status status = session.executeSchemeQuery(yql).join();
            if (!status.isSuccess()) {
                throw new IllegalStateException("DDL failed: " + yql + " -> " + status);
            }
        }
    }

    protected static String randomPrefix() {
        return "poc" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static final java.util.List<String> TRACKED_PREFIXES =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** Schema creation that registers for @AfterAll cleanup (no silent table leaks). */
    protected static String trackedSchema(String prefix) {
        YdbSchema.ensureSchema(client(), prefix);
        TRACKED_PREFIXES.add(prefix);
        return prefix;
    }

    /**
     * Drops all tracked schemas. Called explicitly from each test class's own
     * {@code @AfterAll} — inherited {@code @AfterAll} in this base never runs
     * because no test class extends it (verified 2026-09-27: silent no-cleanup).
     */
    public static void dropAllTracked() {
        for (String prefix : TRACKED_PREFIXES) {
            YdbSchema.dropSchema(client(), prefix);
        }
        TRACKED_PREFIXES.clear();
    }

    private static String firstExisting(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank() && java.nio.file.Files.isReadable(java.nio.file.Paths.get(c))) {
                return c;
            }
        }
        throw new IllegalStateException(
                "YDB CA not found; copy it out with: docker cp ydb-poc:/ydb_certs/ca.pem /tmp/ydb-ca.pem"
                        + " (or set YDB_CA_PATH)");
    }
}
