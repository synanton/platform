package org.synanton.synquest.postgres;

import java.util.Map;
import org.synanton.storage.contract.Capabilities;
import org.synanton.storage.contract.Conformant;
import org.synanton.storage.testkit.ConformanceGatingContract;

/**
 * PG-POC-007 gating contract (007-1: scaffold). Expected GREEN — §9.3
 * discipline from day one: every flag is {@code false}, every matrix entry
 * {@code unverified}. A flag may flip only with its evidence task.
 */
class PostgresSynquestGatingTest extends ConformanceGatingContract {

    private PostgresSynquestEngine engine;

    @Override
    protected Conformant adapter() {
        if (engine == null) {
            engine = new PostgresSynquestEngine(UnconnectedDataSource.instance());
        }
        return engine;
    }

    @Override
    protected Map<String, Boolean> claimedFlags() {
        var flags = ((PostgresSynquestEngine) adapter()).capabilities();
        return Map.of(
                Capabilities.SYNQUEST_LEXICAL, flags.lexical(),
                Capabilities.SYNQUEST_VECTOR, flags.vector(),
                Capabilities.SYNQUEST_HYBRID, flags.hybrid(),
                Capabilities.SYNQUEST_FILTERS, flags.filters(),
                Capabilities.SYNQUEST_HIGHLIGHTS, flags.highlights(),
                Capabilities.SYNQUEST_ELIGIBILITY, false,
                // Temporal rejection lands in 007-7; until then the flag is
                // false (cassandra maps !temporal() because theirs is done).
                Capabilities.SYNQUEST_TEMPORAL_REJECTION, false,
                Capabilities.SYNQUEST_ORDERING, false,
                Capabilities.SYNQUEST_GENERATION_DELETE, false);
    }
}
