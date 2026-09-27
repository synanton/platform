package org.synanton.synquest.ydb;

import java.util.Map;
import org.synanton.storage.contract.Capabilities;
import org.synanton.storage.contract.Conformant;
import org.synanton.storage.testkit.ConformanceGatingContract;

class YdbSynquestGatingTest extends ConformanceGatingContract {

    private YdbSynquestEngine engine;

    private YdbSynquestEngine engine() {
        if (engine == null) {
            YdbSearchTestBase.ensureStarted();
            String prefix = "t_quest_gating";
            YdbSearchSchema.ensureSchema(YdbSearchTestBase.client(), prefix, 2);
            YdbSearchSchema.truncateAll(YdbSearchTestBase.client(), prefix);
            engine = new YdbSynquestEngine(YdbSearchTestBase.client(), prefix);
        }
        return engine;
    }

    @Override
    protected Conformant adapter() {
        return engine();
    }

    @Override
    protected Map<String, Boolean> claimedFlags() {
        var flags = engine().capabilities();
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
