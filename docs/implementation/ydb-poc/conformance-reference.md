# Conformance reference (frozen rule + per-adapter entries)

**Rule (§9.3, executable): Must rows gate against the requirement, not the
baseline. Baseline non-conformance is recorded, not inherited.** "YDB matches
baseline" closes nothing where the baseline is absent/unspecified. Same for
unmeasured metrics: gate absolutes (vector Recall@10 ≥ 0.95), never nulls.
Full matrix: `004-parity-matrix.md`.

## Baseline non-conforming flags

- Highlight offsets — not implemented anywhere in the service.
- Metadata operators — none on the search path.
- Tie-breaking — unspecified (stream order).

## Per-adapter conformance (machine-readable via `Conformant.conformance()`)

| Capability | inmemory (vault) | cassandra | inmemory (quest) |
|---|---|---|---|
| revision / atomicity | SUPPORTED | **UNSUPPORTED (008)** | n/a |
| delete | SUPPORTED | **UNSUPPORTED (008)** | generation-scoped SUPPORTED |
| document / chunks / provenance / pagination | SUPPORTED | SUPPORTED | n/a |
| OCC revisions | SUPPORTED | **UNSUPPORTED (008)** | n/a |
| lexical / vector / hybrid / filters / highlights | n/a | n/a | SUPPORTED |
| pre-ranking eligibility / temporal-rejection / ordering | n/a | n/a | SUPPORTED |

Gating suite (`ConformanceGatingContract`) enforces: claimed-`true` flags must be
`SUPPORTED` with a loadable evidence test class; `UNSUPPORTED` rows carry reasons.
YDB rows fill when 024B lands.
