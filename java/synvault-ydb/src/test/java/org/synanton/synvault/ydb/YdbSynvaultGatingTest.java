package org.synanton.synvault.ydb;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.synanton.storage.contract.Capabilities;
import org.synanton.storage.contract.Conformant;
import org.synanton.storage.testkit.ConformanceGatingContract;

/** Gating suite for the YDB matrix — all synvault capabilities SUPPORTED per 020. */
class YdbSynvaultGatingTest extends ConformanceGatingContract {

    private static String prefix;
    private static YdbSynvaultStore store;

    @BeforeAll
    static void ensureSchema() {
        YdbTestBase.ensureStarted();
        prefix = "t_vault_gating";
        YdbSchema.ensureSchema(YdbTestBase.client(), prefix);
        YdbSchema.truncateAll(YdbTestBase.client(), prefix);
        store = new YdbSynvaultStore(YdbTestBase.client(), prefix, "gating-" + UUID.randomUUID());
    }

    @Override
    protected Conformant adapter() {
        return store;
    }

    @Override
    protected Map<String, Boolean> claimedFlags() {
        var flags = store.capabilities();
        return Map.of(
                Capabilities.SYNVAULT_REVISION, flags.supportsTransactions(),
                Capabilities.SYNVAULT_DELETE, true,
                Capabilities.SYNVAULT_DOCUMENT, true,
                Capabilities.SYNVAULT_CHUNKS, true,
                Capabilities.SYNVAULT_PROVENANCE, flags.supportsProvenance(),
                Capabilities.SYNVAULT_PAGINATION, flags.supportsCursorPagination(),
                Capabilities.SYNVAULT_OCC, flags.supportsStorageRevisions());
    }

}
