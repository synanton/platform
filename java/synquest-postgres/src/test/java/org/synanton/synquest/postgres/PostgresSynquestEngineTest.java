package org.synanton.synquest.postgres;

import org.synanton.storage.testkit.SynquestEngineContract;
import org.synanton.synquest.api.SynquestEngine;
import org.synanton.synquest.api.SynquestIndexAdmin;
import org.synanton.synquest.api.SynquestIndexWriter;

/**
 * PG-POC-007 contract suite (007-1: scaffold). Expected RED — every port
 * method is an honest {@code UnsupportedOperationException} until its task
 * lands (007-2 onward). Progress on this ticket is measured by these
 * failures shrinking, never by skipping them.
 */
class PostgresSynquestEngineTest extends SynquestEngineContract {

    private static PostgresSynquestEngine engine() {
        // Unconnected stub: scaffold methods never reach the database.
        return new PostgresSynquestEngine(UnconnectedDataSource.instance());
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
    protected SynquestIndexAdmin newAdmin() {
        return engine();
    }
}
