# Vertical Slice Plan — Ingestion → Content Cache → Eventing → Knowledge → Search

Parent: Architecture 1.0 §14 step 3 (after 1.32 + 1.27 freezes — entry gate,
not work items here). Acceptance: §16 "First end-to-end vertical slice is
reproducible" (open).
Track convention: per-plane dir + INDEX.md (see implementation INDEX).

## State going in (recon 2026-10-12)

| Plane | State | Evidence |
|---|---|---|
| Ingestion | PARTIAL | synflux pipeline works (extract/chunk/embed/persist); extraction server external; SCEP-1/2/4/5 done |
| Content Cache | MISSING | No module; 1.26 approved-unimplemented. `ingestion-cache` is an ingestion store, NOT the artifact contract |
| Eventing | STUB | Kafka transport works (topics, consumers, backpressure); plane missing (EWP-0–6 not started); outbox never publishes (TODO Phase 4); no envelope/correlation IDs |
| Knowledge | EXISTS | syntology (Jena/TDB2, SHACL) + relix (in-memory graph, executors) working |
| Search | EXISTS | synquest hybrid service working, boot rebuild, full bench coverage |

## Phases

### VS-0 — Entry: freezes executed (dependency, no work here)

- 1.32 + 1.27 freeze packets executed (packets exist; scheduling is the owner's).
- Do not start VS-1 until both are frozen — the slice integrates contracts, not drafts.

### VS-1 — Content Cache minimal artifact contract (the hole)

- Problem: nothing owns the content artifact as a contract (manifest +
  chunks + embeddings + generation pointer, versioned, addressable).
- Minimal build: define the artifact record (new small module or an
  explicitly-contracted view over `ingestion-cache` tables — decision at
  kickoff; bias to the view unless versioning forces a module), with
  generation pinning (Architecture invariant 35: projections reproducible).
- Acceptance: one document's full artifact readable by ID + generation;
  schema reviewed against 1.26 (not re-derived).

### VS-2 — Eventing contract through the slice (EWP-1 applied)

- Replace the three stub publishes (TopologyOutboxDispatcher TODO,
  hardcoded OutboxPublisher, FileEventLogger) with EWP-1 envelope events
  carrying tenant scope + correlation/causation IDs.
- Scope: slice events only (ingested → cached → indexed); not the full
  EWP-2–6 reliability program (retries/DLQ/backpressure already work at
  transport level).
- Acceptance: event log shows the full chain per document with joinable IDs.

### VS-3 — Slice integration (one doc, end to end)

- Single document through: ingest → artifact → events → knowledge
  (syntology + relix) → search, asserting each handoff (artifact ID,
  event IDs, graph nodes, index hits).
- Reproducibility check: tear down derived state (index + graph deltas),
  replay from the artifact, byte-identical results (invariant 35).
- Acceptance: green run scripted (one command), logged in the tracker.

### VS-4 — Acceptance (§16 box)

- "First end-to-end vertical slice is reproducible": VS-3 script green on
  a clean checkout + fresh stacks, witnessed (not self-attested).
- Then: Identity/security across the slice (§14 step 4) becomes schedulable.

## Non-goals (explicit)

- Full EWP reliability program, workflow engine, analytics consumer,
  K8s review, Go operators — those are §14 steps 4–9, sequenced after.
- New retrieval semantics — the slice uses the search plane as-is.
- Production hardening of any plane — PoC-grade integration only.
