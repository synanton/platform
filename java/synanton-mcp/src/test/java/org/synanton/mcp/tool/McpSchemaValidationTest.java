package org.synanton.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import org.synanton.mcp.app.McpProperties;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Row 4b / W5 / WS0-crit-3 validation run: the tools/list aggregate validates
 * against the declared 2025-03-26 surface (MCP-COMPLIANCE.md). Scope-decl
 * resolution (a): the revision defines no scope member on Tool, so
 * server-side-only enforcement conforms and no schema change was needed.
 */
class McpSchemaValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final tools.jackson.databind.ObjectMapper TOOLS_MAPPER =
            new tools.jackson.databind.ObjectMapper();
    private static final SchemaRegistry REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private final McpProperties props = new McpProperties(
            null, null, null, null, 5000, null);

    private ToolRegistry registry() {
        return new ToolRegistry(List.of(
                new SearchToolHandler(props),
                new GraphQueryToolHandler(props),
                new OntologyResolveToolHandler(props),
                new IngestDocumentToolHandler(),
                new ListTenantsToolHandler(),
                new SynthesiseToolHandler(),
                new Phase4StubTools()));
    }

    // Check 1 — envelope floor: every listed tool carries at least
    // name, description, inputSchema (annotations optional per the revision).
    @Test
    @SuppressWarnings("unchecked")
    void toolsListEnvelope_carriesNameDescriptionSchema() {
        List<Map<String, Object>> tools = registry().listTools();

        assertThat(tools).isNotEmpty();
        for (Map<String, Object> tool : tools) {
            assertThat(tool).containsKeys("name", "description", "inputSchema");
            assertThat((String) tool.get("name")).isNotBlank();
            assertThat(tool.get("inputSchema")).isInstanceOf(Map.class);
        }
    }

    // Check 2 — every inputSchema compiles as a JSON Schema 2020-12 document
    // and enforces its own required fields (search shown: query required).
    @Test
    void inputSchemas_compileAs202012_andEnforceRequired() throws Exception {
        Schema search = null;
        for (Map<String, Object> tool : registry().listTools()) {
            Schema compiled = compile((String) tool.get("name"), tool.get("inputSchema"));
            assertThat(compiled).as("schema compiles: %s", tool.get("name")).isNotNull();
            if ("search".equals(tool.get("name"))) {
                search = compiled;
            }
        }
        assertThat(search).isNotNull();
        List<Error> valid = search.validate(TOOLS_MAPPER.readTree("{\"query\":\"q\",\"top_k\":5}"));
        List<Error> missingQuery = search.validate(TOOLS_MAPPER.readTree("{\"top_k\":5}"));
        assertThat(valid).isEmpty();
        assertThat(missingQuery).isNotEmpty();
    }

    private Schema compile(String toolName, Object schemaMap) throws Exception {
        String json = MAPPER.writeValueAsString(schemaMap);
        try {
            return REGISTRY.getSchema(
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), InputFormat.JSON);
        } catch (Exception e) {
            throw new AssertionError("inputSchema does not compile as 2020-12: " + toolName, e);
        }
    }

    // Check 3 — scope-decl (a): no tool declares scopes; enforcement is
    // server-side only (ScopeEnforcer REQUIRED map) and the revision
    // (schema.ts Tool: name/description?/inputSchema/annotations?) requires
    // no declaration. Absence here is conformance, not omission.
    @Test
    @SuppressWarnings("unchecked")
    void inputSchemas_declareNoScopes_serverSideEnforcementStands() {
        for (Map<String, Object> tool : registry().listTools()) {
            Map<String, Object> schema = (Map<String, Object>) tool.get("inputSchema");
            Map<String, Object> properties =
                    (Map<String, Object>) schema.getOrDefault("properties", Map.of());
            assertThat(properties).doesNotContainKey("scope");
            assertThat(properties).doesNotContainKey("scopes");
        }
        assertThat(new ScopeEnforcer().requiredScope("search")).isEqualTo("search");
    }
}
