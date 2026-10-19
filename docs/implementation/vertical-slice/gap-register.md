# Vertical slice — gap register

**Status:** Active · **Last reviewed:** 2026-10-19

Every known gap in the slice, with its trigger condition, current status, and
resolution criterion. A gap without a trigger is not registered — it is
undiscovered.

Format: **what it is** · **when it matters** · **resolution** · **owner**

---

## Correctness

### G-1 · FeatureState anti-omission (#294)

**What:** The extractor returns `FEATURE_UNSUPPORTED` for a requested feature
when an adapter is a stub. The platform reports `SUCCEEDED` + `outcome: FULL`.
Design 1.28's extraction contract states a feature not applied must not be
reported as success by omission.

**When it matters:** any source whose media type routes to a stubbed adapter
(`audio`, `image`, `video` in v1.21). The slice's current path (`document_text`,
`document_pdf`) has both adapters active, so the gap does not fire today.

**Resolution:** `details.feature_states` on the Operation (W2 `details` is
open-typed — no contract revision); `outcome: PARTIAL` when any requested
feature is `FEATURE_UNSUPPORTED` or `FEATURE_NOT_APPLICABLE`. Test:
`FEATURE_UNSUPPORTED → outcome: PARTIAL`.

**Owner:** unassigned. **Ticket:** #294.

---

### G-2 · Single-reviewer control at M0 row 6

**What:** A, S, and DX sign-off for M0 row 6 is held by one person, who also
authored the D1/D4/error-model artifacts being signed.

**When it matters:** at row 6 sign-off. The evidence is fresh and citable, but
the control is self-review.

**Resolution:** either name a second Security reviewer for row 6, or record the
single-person control as an accepted resourcing risk in the row's State cell.

**Owner:** unassigned. **Ticket:** none — one-line row edit.

---

## Test infrastructure

### G-3 · Jackson databind/core classpath split (#238)

**What:** `jackson-databind` resolves to 2.18.11 while `jackson-core` and
`jackson-annotations` resolve to 2.17.2. First surfaced as `NoSuchMethodError`
on `ObjectMapper.valueToTree` when the first test under
`synanton-mcp/src/test` was written.

**When it matters:** any new test that exercises Jackson on the test classpath.
The slice's tests currently avoid the affected call path.

**Resolution:** align on one `jackson-bom` — either constrain the BOM to
2.18.11 or drop the catalog pin to Boot-managed.

**Owner:** unassigned. **Ticket:** #238.

---

### G-4 · Acceptance suite not audited for synthetic-shape tests

**What:** The four-instance pattern (tests asserting shapes production never
produces) was found four times during development. The acceptance suite has
not been audited against the same question.

**When it matters:** at the next acceptance run. A pass on a synthetic shape
is a false positive that survives CI.

**Resolution:** per assertion in `tests/integration/vertical-slice.sh`, ask
"could this pass while production is broken?" Record the audit and fix anything
found.

**Owner:** unassigned. **Ticket:** none — see `trust-bar.md` question 2.

---

## Coverage

### G-5 · PostgreSQL live-test suites excluded from CI (#287)

**What:** Three Postgres Testcontainers suites are excluded from `buildAll` on
the constrained runner (exit 137 / OOM). They are tracked in #287 but currently
run nowhere in CI.

**When it matters:** the extraction gateway's operation store is Postgres. A
regression in that layer would not be caught by CI.

**Resolution:** a database-enabled CI job, or restore the suites on a larger
runner. #287 distinguishes "restore on a larger runner" from "add a database-
enabled job" — the ticket should state which.

**Owner:** unassigned. **Ticket:** #287.

---

### G-6 · No load test; no multi-component failure test

**What:** The slice is tested at acceptance volume on a single component
failure. No 10× load scenario, no two-component-down scenario.

**When it matters:** when the slice is depended on, not just demonstrated. Not
relevant to the trust bar's current scope.

**Resolution:** one load scenario at 10× acceptance volume; one scenario where
Cassandra and the extractor are both unavailable.

**Owner:** unassigned. **Ticket:** none — future milestone.

---

## Structural limitations (documented, not defects)

### G-7 · `aud` claim is structural, not enforced

**What:** `context_token` carries `aud: "synanton-internal"` — a single shared
marker. `HMACVerifier` does not check it, and a strict check could not
distinguish services because the value is identical for every envelope.

**When it matters:** cross-service replay of a valid envelope. Mitigated by
token lifetime (`expires_at`) and each consumer's authorization checks on the
claims — not by the envelope.

**Resolution:** none. Recorded in ADR-019 as a design property. A per-service
audience would be a wire-format change and a provider release.

**Owner:** — **Ticket:** —

---

### G-8 · Search is BM25-only

**What:** `metadata.strategy` returns `"bm25"`. The frozen W3 contract
anticipated this: `filters` and `cursor` are Gen-B, and the dense/hybrid leg
remains available through the existing hybrid service.

**When it matters:** when retrieval quality matters more than path correctness.
Not relevant to the slice's acceptance criteria.

**Resolution:** the next milestone — baseline, dense leg, comparison, hybrid.

**Owner:** — **Ticket:** — (search improvements)

---

### G-9 · Provider config gap for end-to-end browser demo

**What:** `security-provider/configs/local.yaml` has `providers: []`. The
predicate is executable against a running provider, but an end-to-end
browser → synapt envelope demo needs an auth provider configured in the
provider's own stack.

**When it matters:** only for a browser-originated demo. The predicate and its
twelve-case test matrix work today.

**Resolution:** the security-provider's own dev setup — deployment item, not a
predicate gap.

**Owner:** — **Ticket:** —

---

### G-10 · No independent reproduction

**What:** The repeatability gate passed twice, both on developer machines with
warm caches, local secrets, and registry credentials.

**When it matters:** before anyone else relies on the pass. A fresh clone on a
different host would hit setup issues the current evidence does not cover.

**Resolution:** one clean-checkout run on a host that has never run the slice.
Record what breaks.

**Owner:** unassigned. **Ticket:** none — see `trust-bar.md` question 1.
