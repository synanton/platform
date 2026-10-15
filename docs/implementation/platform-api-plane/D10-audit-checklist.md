# D10 Audit Checklist — synanton-mcp conformance (WS3 gate)

**Timebox:** 3–5 working days. **Deliverable:** written audit note (D10).
**Default on timeout:** refactor, not rebuild.
**Spec pin:** record the MCP spec revision (dated) + authorization-spec
sections at audit start; no drifting mid-audit.

## Q1 — Transport: Streamable HTTP vs older

- [ ] Identify the transport the module serves (check `McpEndpoint` +
      deployment descriptors, not README claims).
- [ ] If SSE/legacy: scope the migration to Streamable HTTP (headers,
      session handling, backwards-compat obligations).
- [ ] Record: transport name + spec section.

## Q2 — Protected resource metadata (MCP authorization spec)

- [ ] Does the module publish protected-resource metadata discoverable by
      clients (per the pinned authorization spec)?
- [ ] If absent: scope adding it (endpoint + metadata shape + tests).
- [ ] Record: metadata URL + spec section.

## Q3 — Token validation depth

- [ ] Audience binding: wrong-audience tokens rejected (negative test).
- [ ] Scope: search/MCP scopes enforced, not merely parsed.
- [ ] Tenant: principal→tenant resolution present on every call path.
- [ ] Passthrough rejection: explicit test that a caller token is never
      forwarded as the downstream credential (confused-deputy probe).
- [ ] Record: validation checklist with test names per item.

## Forced decision (end of timebox)

**integrate-and-conform** (gaps are additive; keep module) /
**refactor** (structure wrong, behavior right; default on timeout) /
**rebuild** (only with evidence the module cannot meet (a)+(b)+(c)).

Record the decision + rationale in the audit note. D4 finalizes after.
