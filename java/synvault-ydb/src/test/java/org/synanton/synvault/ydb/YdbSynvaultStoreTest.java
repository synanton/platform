package org.synanton.synvault.ydb;

import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.synanton.storage.testkit.SynvaultStoreContract;
import org.synanton.synvault.api.SynvaultStore;

/**
 * {@link SynvaultStoreContract} against live YDB (021). One table set per class
 * (random prefix); row isolation via per-store tenant namespaces.
 */
class YdbSynvaultStoreTest extends SynvaultStoreContract {

    private static String prefix;

    @BeforeAll
    static void ensureSchema() {
        YdbTestBase.ensureStarted();
        prefix = "t_vault_store";
        YdbSchema.ensureSchema(YdbTestBase.client(), prefix);
        YdbSchema.truncateAll(YdbTestBase.client(), prefix);
    }

    @Override
    protected SynvaultStore newStore() {
        return new YdbSynvaultStore(
                YdbTestBase.client(), prefix, "test-" + UUID.randomUUID());
    }

}
