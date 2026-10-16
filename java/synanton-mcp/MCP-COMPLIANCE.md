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
against the declared surface above. Validator, revision alignment, and result:
<to be recorded on completion>.
