package org.synanton.synquest.inmemory;

import java.util.Map;
import org.synanton.storage.contract.Capabilities;
import org.synanton.storage.contract.Conformant;
import org.synanton.storage.testkit.ConformanceGatingContract;

class InMemorySynquestGatingTest extends ConformanceGatingContract {

    private final InMemorySynquestEngine engine = new InMemorySynquestEngine();

    @Override
    protected Conformant adapter() {
        return engine;
    }

    @Override
    protected Map<String, Boolean> claimedFlags() {
        var flags = engine.capabilities();
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
