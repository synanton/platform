package org.synanton.synquest.inmemory;

import org.synanton.storage.testkit.SynquestEngineContract;
import org.synanton.synquest.api.SynquestEngine;
import org.synanton.synquest.api.SynquestIndexWriter;

class InMemorySynquestEngineTest extends SynquestEngineContract {

    private final InMemorySynquestEngine engine = new InMemorySynquestEngine();

    @Override
    protected SynquestEngine newEngine() {
        return engine;
    }

    @Override
    protected SynquestIndexWriter newWriter() {
        return engine;
    }
}
