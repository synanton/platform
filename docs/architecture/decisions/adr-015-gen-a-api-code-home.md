# ADR-015: Gen-A API Code Home — New `java/platform-api`, `synapt` Frozen

**Status:** Accepted 2026-10-16
**Basis:** BBAG-A's Gen-A slice (Login/Logout, Search API, MCP search adapter) needs a code home before M0 freeze; PAPI-0 remains undecided and `settings.gradle.kts` contains neither `java/platform-api` nor a `synapt` decision. Module home is an internal packaging choice — the external contract (`/v1/...` paths, Appendix-C errors, Operation shape) is invariant under it, so reversal cost is a Gradle include change plus a package rename.

## Decision

1. **Gen-A lands in a new `java/platform-api` module**, added to `settings.gradle.kts` at WS0. The module is born with `/v1/` routes, Appendix-C error shapes, and `request_id` propagation — PAPI-1 conventions apply to Gen-A code even though PAPI-1 is not formally started.
2. **`java/synapt` is frozen for the duration of Gen-A** — no new external endpoint, resource, or route; existing endpoints unchanged; no code moves in or out. Freezing rather than retiring keeps the PAPI-0 option set open at zero cost.
3. **`platform-api` is scoped to the Gen-A slice only** — Login/Logout, Search, MCP search adapter. No PAPI-2 resource surface, PAPI-3 Operation machinery, or PAPI-7 governance apparatus is pre-built.
4. **`java/synanton-mcp` is not moved**; its D10 refactor (2026-10-15, decision: refactor) proceeds in place against work items 1–7.

Consequences:
- **M0:** unblocked on the module-home question with a ~1-day decision instead of a full PAPI-0 cycle.
- **Gen-A:** code is born conformant to PAPI-1 conventions, so PAPI-1 inherits a foundation rather than retrofitting one.
- **`synapt`:** fate stays genuinely open — freeze is reversible; expansion-by-default is not.
- **Accepted costs:** `platform-api` exists before PAPI-0 formally closes, so the name is chosen under Gen-A pressure rather than in the abstract; two modules with external surfaces coexist during Gen-A; frozen `synapt` may draw "just add one endpoint" pressure, mitigated by the freeze being explicit and reviewable.
- **Deferred to full PAPI-0:** `synapt`'s terminal fate (retire/internalize/merge); full PAPI-1..7 module and package layout; whether `synanton-mcp` is eventually absorbed or stays a sibling; the `/v1beta/` preview-surface boundary; all PAPI-2/PAPI-3 scope, including the `ExtractionStatus` → Operation-state reconciliation.
- **Rejected alternatives:** landing in `synapt` (no `/v1/` convention or resource model; converts expansion into the path of least resistance); full-PAPI-0-first (delays M0 for inputs Gen-A does not need); provisional `java/gena-api` (deliberately temporary name guarantees a rename with no offsetting benefit).
- **Neutral:** no change to any internal service-to-service contract (`synanton.extraction.v1`, `synanton.gpu.v1`, etc.).

## Context

BBAG-A's Gen-A slice is a vertical cut through PAPI-1 and PAPI-4, arriving before either phase formally begins — which is why it needs a home at all. `INDEX.md` declares PAPI-0 a gate for all else and is stale at 2026-09-15; `settings.gradle.kts` has no `java/platform-api` while `java/synapt` and `java/synanton-mcp` exist. Leaving the question undecided does not preserve optionality — it transfers the decision to whoever lands code first, the outcome PAPI-0 exists to prevent. Full PAPI-0 follows before PAPI-1.
