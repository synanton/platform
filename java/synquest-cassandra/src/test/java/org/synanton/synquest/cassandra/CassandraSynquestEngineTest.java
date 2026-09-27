package org.synanton.synquest.cassandra;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.storage.contract.Capabilities;
import org.synanton.storage.contract.Conformant;
import org.synanton.storage.testkit.ConformanceGatingContract;
import org.synanton.storage.testkit.SynquestEngineContract;
import org.synanton.synquest.api.SynquestEngine;
import org.synanton.synquest.api.SynquestIndexWriter;

class CassandraSynquestEngineTest extends SynquestEngineContract {

    @TempDir
    private Path root;

    private CassandraSynquestEngine engine;

    private CassandraSynquestEngine engine() {
        if (engine == null) {
            engine = new CassandraSynquestEngine(root.resolve("idx-" + System.nanoTime()));
        }
        return engine;
    }

    @Override
    protected SynquestEngine newEngine() {
        return engine();
    }

    @Override
    protected SynquestIndexWriter newWriter() {
        return engine();
    }

    @Override
    protected org.synanton.synquest.api.SynquestIndexAdmin newAdmin() {
        return engine();
    }
}
