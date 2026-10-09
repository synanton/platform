# Benchmark Runner Requirements (BR-A0.1) — CLOSED

**Task:** BR-A0.1 — Gather requirements from Track B
**Status:** Closed 2026-10-09 — owner decisions recorded below; open questions resolved.
**Depends on:** Track A + Track B owners named (Week-1 gate closed 2026-10-01) ✓
**Design anchors:** Platform Architecture 1.0 invariants + §§6/11/13/14; Manifests,
EventLab, Benchmark-runner, Versioning proposals where 1.0 is silent.

---

## 1. Composition description format — DECIDED

**1a. Composition IDs: runner-assigned slug.** `composition_id` = slug of
`metadata.provider + vector.provider + index-params-hash`, recorded in both
manifests. Stable across runs, no central allocator. Rejected: Track-B-assigned
human names (central bottleneck, renames break history). Anchor: Arch 1.0 #9
(identity names, digest verifies), #12.

**1b. Secrets: by-name env refs, resolved at runner start.** `${VAR}`
interpolation, mirroring the platform compose/`.env` pattern. Manifests stay
write-once and shareable. Rejected: vault integration (new dependency pre-B5).
`.env` is git-ignored (`.gitignore:43`, verified); runner secret contract lives in
`.env.default` (commented, placeholder values — proposed names, normed at BR-A0.2
schema freeze). Missing ref fails loud, never guessed (Arch 1.0 #7); secrets must
not leak into shareable contracts (#25).

## 2. Corpus reference format — DECIDED

**2a. Content-addressed URI + alias.** `cas:<sha256>` with human alias, resolved
via a corpus registry file pinned in the manifest. The hash *is* the identity;
verification trivial. Rejected: plain paths (mutable targets break reproducibility
silently). Anchor: #9, #35.

**2b. Track B owner publishes `dataset_version`.** Content hash recorded in the
corpus definition; runner cross-checks before execution. Rejected: runner-computed
(first run has nothing to compare against). Anchor: #12, #35.

## 3. Metric shape — DECIDED

**3a. Narrow mandatory set.** Mandatory: recall@10, eligible-set identity, p95
latency, reproducibility flags, per-query topology. Nullable: NDCG/MRR, build
time/size, freshness, RAG quality. Honors "no metric without topology"; deferred
metrics may require re-runs. Rejected: all-mandatory (B5 blocks on hardest
measurements). Anchor: Arch 1.0 §13, metric taxonomy (BR-A0.4).

**3b. RAG answer quality: deferred.** Runner measures retrieval; answer quality
belongs to gateway/LLM eval. Keeps B5 unblocked. Rejected: in-scope (needs judge
model + rubric + cost). Anchor: design 1.31 benchmark scope (recall/NDCG/latency).

## 4. Reproducibility — DECIDED

**4a. Control sampling + order; document build nondeterminism.** Pin query
sampling, corpus order, all controllable seeds; name Lucene merge-order
nondeterminism as uncontrolled (bit-identical indexes not guaranteed). Rejected:
single-threaded fixed-seed builds (unrepresentative, slow). Anchor: #35.

**EventLab review (operator-requested, recorded here):** EventLab
(`../eventlab`, README-only design spike, blank project) is the *intended*
deterministic-sampling source — workload identity (generator version + schema +
PRNG + seed + config + canonical serialization, R1–R7 contract) maps directly
onto the runner's corpus-verification needs. But it is unimplemented (Phase 0
spike unresolved): the dependency is future, recorded as follow-on, not
assumed. Until EventLab lands, the runner works on the demo-data corpus with
content-hash verification. No plan text may cite EventLab as an available input.

**4b. Lightweight env record now, digests later.** Engine + runner versions,
model ids/dims, `dataset_version`, topology. Container digests parked as Phase-5
hardening. Anchor: #31, #12.

## 5. Execution mode — DECIDED

**5a. Per composition, per-query override optional.** Mirrors VEC-B4.2's own
`executionMode` + deployment-default design; mixed-mode comparisons expressible
in one manifest; mode recorded either way. Rejected: inherit-default always
(cannot express B5's comparisons). Anchor: VEC-B4.2, #35.

**5b. Crossover measurement: analysis-side.** Runner emits per-leg timings;
crossover computed from artifacts, re-runnable without re-execution. Executor
stays dumb. Anchor: #11/#32 (derived state).

## 6. Outputs — DECIDED

**6a. Proposal §10 layout + local mirror.** `s3://synvault/manifests/benchmark/<id>.json`,
`.../result/<id>.json`, `.../runs/<id>/…`, with local-file mirror for pre-S3 runs.
Write-once. Needs the bucket to exist (follow-on). Anchor: Runner proposal §10;
Arch 1.0 #21 (large payloads by reference).

**6b. Eventing 1.27 owner; provisional until freeze.** Strongest call in this
record: Arch 1.0 §6 forbids planes inventing separate async semantics and §14
sequences the 1.27 freeze before dependent planes. Runner ships the provisional
`RunCompletedEvent` shape flagged. Rejected: track-local freeze (fast, violates
§6, rework risk).

## 7. Cost controls — DECIDED

**7a. Track A owner approves.** Threshold = event-count × estimated unit cost,
in-manifest. Week-1-named owner accountable; no heavier gate.

## Session exit

All §1–§7 questions answered above. BR-A0.1 closes on merge; answers feed
BR-A0.2 (manifest schema) directly. Follow-ons: EventLab dependency (future),
synvault bucket existence, BR-A0.2 norming of `.env.default` var names.
