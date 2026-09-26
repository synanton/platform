package org.synanton.synvault.inmemory;

import java.util.Map;
import org.synanton.storage.contract.Capabilities;
import org.synanton.storage.contract.Conformant;
import org.synanton.storage.testkit.ConformanceGatingContract;

class InMemorySynvaultGatingTest extends ConformanceGatingContract {

    private final InMemorySynvaultStore store = new InMemorySynvaultStore();

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
