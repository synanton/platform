# ADR-017: Validation Tooling — Vacuum (OpenAPI), networknt (MCP Schema)

**Status:** Proposed 2026-10-16
**Basis:** Four labels hinge on artifact validation and no tooling exists for either artifact type: gate row 3 (D1 OpenAPI lint), row 4b (D4 schema validation), WS0 acceptance criteria "lint passes" and "MCP schema validated". Both artifact types are greenfield — no OpenAPI spec, no JSON-Schema validator on the module classpath (jackson-databind, assertj, mockito only). CI is GitHub Actions + Gradle; the org already carries a Go toolchain via equalix-go.

## Decision

1. OpenAPI linter: Vacuum. Go single-binary. Adoption adds a download step to CI, not a language runtime — the Go toolchain is already present. Vacuum consumes Spectral-compatible rulesets, so a later move to Spectral does not invalidate the ruleset.
2. MCP schema validation: networknt/json-schema-validator + assertj. Two complementary checks, not alternatives:
   - tools/list envelope shape ({tools: [{name, description, inputSchema}]}) → assertj, already on classpath.
   - inputSchema validity as a JSON Schema document → networknt, test-scope only.
   The distinction is load-bearing: assertj can assert inputSchema has type: object and a properties map; it cannot assert that inputSchema is itself a valid schema. WS0's criterion is "MCP schema validated" — that requires a schema validator. networknt is picked over everit because it covers 2020-12 and therefore cannot be wrong about which draft the revision mandates; everit supports only older drafts and leaves that question open.
3. Version policy: pin exact, at adoption. No floating ranges. Exact versions recorded in CI config and appended to this ADR in the same pass that wires the tools in.
4. Scope-declaration resolution is a prerequisite to the validation run, not part of it. Whether 2025-03-26 expects inputSchema to carry a scope/security field is resolved by reading the revision's tools/list and inputSchema requirements before the run. If the spec requires declaration, W5's schema gains a field; the validation then checks the amended shape.

Consequences:
- Row 3 — unblocked on tooling; closeable when a spec exists and lints clean.
- Row 4b, W5, WS0 criterion 3 — one validation run resolves all three; recorded in MCP-COMPLIANCE.md's validation slot.
- WS0 criterion 1 — same tooling, separate run.
- No new runtime for OpenAPI — Vacuum is a binary, not a toolchain.
- New test-scope dep — networknt does not ship in production artifacts.
- Rejected (OpenAPI): Spectral — wider adoption, richer ruleset ecosystem, but requires Node in CI, which the org does not otherwise carry. Revisit if ruleset needs exceed Vacuum's compatibility. Redocly CLI — same npm cost, plus a docs-hosting half that isn't required.
- Rejected (MCP): everit — older drafts only. assertj-only — cannot validate inputSchema as a schema document.
- Not decided here: the method for the scope-declaration question (spec read vs reference probe) — procedure is required by Decision 4; method is DX's call.

## Context

Both activities were labeled "P2" and "P1" without distinguishing what each needed. PR 218 resolved P2's factual half — revision is 2025-03-26, implemented as a stateless three-method subset. What remained for both artifact types was tooling selection, which is the same class of question, and is decided here in one pass. The two picks close four labels: two gate rows and two WS0 criteria.
