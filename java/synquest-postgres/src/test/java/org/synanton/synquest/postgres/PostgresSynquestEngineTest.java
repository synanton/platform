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
}
