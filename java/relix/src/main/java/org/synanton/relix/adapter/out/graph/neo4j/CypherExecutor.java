package org.synanton.relix.adapter.out.graph.neo4j;

import java.util.List;
import java.util.Map;

/**
 * Thin Cypher port so {@link Neo4jGraphConnector} does not depend on Driver types in query mapping tests.
 */
public interface CypherExecutor {

    List<Map<String, Object>> read(String cypher, Map<String, Object> params);

    void write(String cypher, Map<String, Object> params);
}
