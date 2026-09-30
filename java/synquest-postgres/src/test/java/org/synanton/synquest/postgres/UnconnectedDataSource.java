package org.synanton.synquest.postgres;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Scaffold {@link DataSource}: every method fails loudly. 007-1 stubs never
 * reach the database; any path that does is a wiring bug, not a silent pass.
 * Replaced by a real container-backed source when retrieval lands (007-2+).
 */
final class UnconnectedDataSource implements DataSource {

    private static final UnconnectedDataSource INSTANCE = new UnconnectedDataSource();

    static DataSource instance() {
        return INSTANCE;
    }

    private static SQLException dead() {
        return new SQLFeatureNotSupportedException("007-1 scaffold: no database behind this source");
    }

    @Override
    public Connection getConnection() throws SQLException {
        throw dead();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw dead();
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("org.synanton.synquest.postgres");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw dead();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return false;
    }
}
