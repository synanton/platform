package org.synanton.synquest.postgres;

import org.junit.jupiter.api.BeforeAll;
import org.synanton.storage.testkit.SynquestEngineContract;
import org.synanton.synquest.api.SynquestEngine;
import org.synanton.synquest.api.SynquestIndexAdmin;
import org.synanton.synquest.api.SynquestIndexWriter;

/**
 * PG-POC-007 contract suite against live Postgres. 007-1: all red stubs.
 * 007-2: lexical + writer legs run live (RLS-scoped app role); vector,
 * hybrid, generation and promotion legs stay red until their tasks — the
 * self-arming suite measures progress with no suite edits.
 */
class PostgresSynquestEngineTest extends SynquestEngineContract {

    @BeforeAll
    static void startInfra() {
        QuestPostgresFixture.ensureStarted();
    }

    @Override
    protected SynquestEngine newEngine() {
        // Per-test isolation (same shape as the synvault store test): the
        // contract reuses fixed chunk ids (c1) and zero-padded toy vectors
        // collide exactly, so every test starts from an empty table.
        // Pointers truncate too: generation adoption is first-write-wins
        // per test, never leaked from the previous one. JUnit runs test
        // classes sequentially — the topology/guard corpora are seeded in
        // their own @BeforeAll, unaffected by these wipes.
        truncateQuestState();
        return QuestPostgresFixture.newEngine();
    }

    @Override
    protected SynquestIndexWriter newWriter() {
        return QuestPostgresFixture.newEngine();
    }

    @Override
    protected SynquestIndexAdmin newAdmin() {
        return QuestPostgresFixture.newEngine();
    }

    private static void truncateQuestState() {
        try (var admin = QuestPostgresFixture.adminConnection();
                var stmt = admin.createStatement()) {
            stmt.execute("TRUNCATE chunks, quest_generations");
        } catch (Exception e) {
            throw new IllegalStateException("truncate failed", e);
        }
    }
}
