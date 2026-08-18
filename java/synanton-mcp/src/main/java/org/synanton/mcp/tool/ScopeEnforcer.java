package org.synanton.mcp.tool;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class ScopeEnforcer {

    private static final Map<String, String> REQUIRED = Map.of(
            "search", "search",
            "graph_query", "graph:read",
            "ontology_resolve", "ontology:read",
            "synthesise", "synthesise",
            "ingest_document", "ingest:write",
            "list_tenants", "tenant:list",
            "list_content", "content:list",
            "execution_trace", "trace:read",
            "usage_summary", "usage:read"
    );

    public boolean allowed(String toolName, Set<String> scopes) {
        String required = REQUIRED.getOrDefault(toolName, toolName);
        return scopes != null && (scopes.contains(required) || scopes.contains("*"));
    }

    public String requiredScope(String toolName) {
        return REQUIRED.getOrDefault(toolName, toolName);
    }
}
