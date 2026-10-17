# MCP Compliance

**Declared revision:** 2025-03-26 — emitted at `McpEndpoint` L35 (`GET /mcp`
capabilities) and L108 (`initialize` response).

**Implemented surface:** a stateless three-method subset — `initialize`,
`tools/list`, `tools/call`. No notifications, progress, sampling, roots, or
session management. Transport is stateless JSON-RPC over HTTP (D10 Q1).

**Read the declared date as a negotiation baseline, not a compliance claim.**
2025-03-26 removed session lifecycle; this module has no sessions to remove, so
the date is not an overstatement of *behavior* — but it does not describe the
implemented surface, because the module implements a subset. The compliance
boundary is the subset: where the revision specifies behavior this module does
not implement, the module is silent, not non-conforming.

**Validation:** the tool-schema aggregate (`ToolRegistry.listTools()` — the
`[{name, description, inputSchema}]` shape emitted by `tools/list`) validates
against the declared surface above.

**Validation record (2026-10-17):**

- **Validator:** `com.networknt:json-schema-validator:3.0.8` (test scope) +
  AssertJ for envelope shape.
- **Revision alignment:** MCP 2025-03-26 three-method subset (declared above).
- **Scope-declaration resolution (a):** the revision's `Tool` type defines
  `name`, `description?`, `inputSchema`, `annotations?` — no scope member at
  any level (`schema/2025-03-26/schema.ts`). Server-side-only enforcement
  conforms; no schema change required.
- **Checks:** (1) `tools/list` envelope floor — every tool carries name,
  description, inputSchema (annotations optional) ✓; (2) each `inputSchema`
  compiles as JSON Schema 2020-12 and enforces `required` ✓; (3) no tool
  declares scopes — absence is conformance, not omission ✓.
- **Run:** `McpSchemaValidationTest` — 3/3 pass; full module suite 58/58.
- **Closes:** row 4b (D4 schema validation), W5 (MCP tool schema), WS0
  acceptance criterion 3 ("MCP schema validated") — one run, three labels.
