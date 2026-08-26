package org.synanton.relix.adapter.out.graph.nebula;

import java.util.List;
import java.util.Map;

/**
 * nGQL session port. Wire the official Nebula Java client (or a test fake) here; Relix domain stays client-free.
 */
public interface NebulaSession {

    void execute(String statement);

    List<Map<String, Object>> query(String statement);
}
