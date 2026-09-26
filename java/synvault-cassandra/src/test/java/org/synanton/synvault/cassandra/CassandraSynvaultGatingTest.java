package org.synanton.synvault.cassandra;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.synanton.storage.contract.Capabilities;
import org.synanton.storage.contract.Conformant;
import org.synanton.storage.testkit.ConformanceGatingContract;

/** Gating suite for the Cassandra matrix — including the 008 UNSUPPORTED rows. */
class CassandraSynvaultGatingTest extends ConformanceGatingContract {

    private static CassandraSynvaultStore store;

    @BeforeAll
    static void startCassandra() {
        CassandraTestBase.ensureStarted();
        store = new CassandraSynvaultStore(CassandraTestBase.client, "gating-" + UUID.randomUUID());
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
                Capabilities.SYNVAULT_DELETE, false,
                Capabilities.SYNVAULT_DOCUMENT, true,
                Capabilities.SYNVAULT_CHUNKS, true,
                Capabilities.SYNVAULT_PROVENANCE, flags.supportsProvenance(),
                Capabilities.SYNVAULT_PAGINATION, flags.supportsCursorPagination(),
                Capabilities.SYNVAULT_OCC, flags.supportsStorageRevisions());
    }
}
