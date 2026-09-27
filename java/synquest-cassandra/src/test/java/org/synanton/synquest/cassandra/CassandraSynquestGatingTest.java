package org.synanton.synquest.cassandra;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.storage.contract.Capabilities;
import org.synanton.storage.contract.Conformant;
import org.synanton.storage.testkit.ConformanceGatingContract;

class CassandraSynquestGatingTest extends ConformanceGatingContract {

    @TempDir
    private Path root;

    private CassandraSynquestEngine engine;

    @Override
    protected Conformant adapter() {
        if (engine == null) {
            try {
                engine = new CassandraSynquestEngine(Files.createTempDirectory(root, "g-"));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return engine;
    }

    @Override
    protected Map<String, Boolean> claimedFlags() {
        var flags = ((CassandraSynquestEngine) adapter()).capabilities();
        return Map.of(
                Capabilities.SYNQUEST_LEXICAL, flags.lexical(),
                Capabilities.SYNQUEST_VECTOR, flags.vector(),
                Capabilities.SYNQUEST_HYBRID, flags.hybrid(),
                Capabilities.SYNQUEST_FILTERS, flags.filters(),
                Capabilities.SYNQUEST_HIGHLIGHTS, flags.highlights(),
                Capabilities.SYNQUEST_ELIGIBILITY, true,
                Capabilities.SYNQUEST_TEMPORAL_REJECTION, !flags.temporal(),
                Capabilities.SYNQUEST_ORDERING, true,
                Capabilities.SYNQUEST_GENERATION_DELETE, true);
    }
}
