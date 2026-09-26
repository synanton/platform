package org.synanton.synquest.ydb;

import java.util.Map;
import org.synanton.storage.contract.Capabilities;
import org.synanton.storage.contract.Conformant;
import org.synanton.storage.testkit.ConformanceGatingContract;
import org.synanton.storage.testkit.SynquestEngineContract;
import org.synanton.synquest.api.SynquestEngine;
import org.synanton.synquest.api.SynquestIndexWriter;

class YdbSynquestEngineTest extends SynquestEngineContract {

    private YdbSynquestEngine engine;

    private YdbSynquestEngine engine() {
        if (engine == null) {
            YdbSearchTestBase.ensureStarted();
            String prefix = YdbSearchTestBase.randomPrefix();
            YdbSearchSchema.ensureSchema(YdbSearchTestBase.client(), prefix, 2);
            engine = new YdbSynquestEngine(YdbSearchTestBase.client(), prefix);
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
}
