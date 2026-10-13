package org.synanton.contentcache.postgres;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Canonical schema installer: {@code schema.sql} is the single DDL
 * definition; tests and the adapter call here, never inline copies.
 * Mirrors the Go service {@code migrations/*.up.sql} statement order.
 */
public final class ContentCacheSchema {

    private ContentCacheSchema() {}

    /** Loads the canonical DDL statements from the classpath resource. */
    public static List<String> statements() {
        try (InputStream in =
                ContentCacheSchema.class.getResourceAsStream("schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("schema.sql not found on classpath");
            }
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>();
            for (String part : sql.split(";\\s*\\n")) {
                String trimmed = stripComments(part).trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
            }
            return List.copyOf(out);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read schema.sql", e);
        }
    }

    private static String stripComments(String part) {
        StringBuilder sb = new StringBuilder();
        for (String line : part.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** Applies the canonical DDL; fails loudly on the first error. */
    public static void ensureSchema(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        try (Statement stmt = connection.createStatement()) {
            for (String ddl : statements()) {
                stmt.execute(ddl);
            }
        }
    }
}
