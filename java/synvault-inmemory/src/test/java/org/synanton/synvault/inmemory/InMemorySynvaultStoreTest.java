package org.synanton.synvault.inmemory;

import org.synanton.storage.testkit.SynvaultStoreContract;
import org.synanton.synvault.api.SynvaultStore;

class InMemorySynvaultStoreTest extends SynvaultStoreContract {
    @Override
    protected SynvaultStore newStore() {
        return new InMemorySynvaultStore();
    }
}
