package org.synanton.relix.adapter.out.graph.nebula;

/**
 * Default bean when {@code relix.graph.connector=nebula} but no {@link NebulaSession} is provided.
 * Replace with a vesoft {@code NebulaPool} adapter (or Testcontainers) in the service that owns graphd.
 */
public class UnconfiguredNebulaSession implements NebulaSession {

    @Override
    public void execute(String statement) {
        throw missing();
    }

    @Override
    public java.util.List<java.util.Map<String, Object>> query(String statement) {
        throw missing();
    }

    private static IllegalStateException missing() {
        return new IllegalStateException(
                "relix.graph.connector=nebula requires a NebulaSession bean "
                        + "(hosts via relix.graph.nebula.graphd-hosts). Domain uses nGQL only through NebulaGraphConnector.");
    }
}
