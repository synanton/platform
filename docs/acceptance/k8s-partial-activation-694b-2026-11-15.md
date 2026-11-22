# K8s #695 partial-activation read acceptance — 2026-11-15

**Status:** accepted; three-state read contract proven on the live cluster.
**Read-path code commit:** `e99156930a93196d075adf78c3475a7ac9748f05` (merge `0a0b73ce`)
**Service deployed:** `synquest` only
**Image:** `local-registry:5000/synanton/synquest@sha256:d46f4bc4139c0a7e19204a3be17e998f18d8189f85c5886b349cd7bb92c3c8a8`
**Pod image ID:** same digest (verified)
**Artifacts:** [`artifacts/k8s-partial-activation-694b-2026-11-15/`](artifacts/k8s-partial-activation-694b-2026-11-15/)

## Contract under test (ADR-029)

The previous revision remains authoritative until the replacement is ready:

| State | Expected read behavior |
|---|---|
| PROMOTING (V2 extracted/mentions committed, not promoted) | V1 fact searchable; V2 fact not searchable |
| GRAPH_COMMITTED (V2 graph written, not activated) | V1 fact searchable; V2 fact not searchable |
| ACTIVE (V2 activated) | V2 fact searchable; V1 fact filtered |

No mixed-generation result at any state.

## Live run

Source: `acceptance-695e-1794732035`, resource `partial-activation.txt`.
V1 fact token: `alpha695final1794732035`; V2 fact token: `beta695final1794732035`.

| State | Cassandra | Search alpha (V1) | Search beta (V2) |
|---|---|---|---|
| V1 ACTIVE | `current_generation=1`, activation `CURRENT` | found | — |
| PROMOTING | V2 `KNOWLEDGE_READY` / `MENTIONS_COMMITTED`, `search_projected_at` set, `current_generation=1` | found | **not found** |
| GRAPH_COMMITTED | V2 `GRAPH_COMMITTED`, `activation_event_published=false`, `current_generation=1`, activation state `GRAPH_COMMITTED` | found | **not found** |
| ACTIVE | `current_generation=2`, `active_revision_id=V2`, activation `CURRENT` | **not found** | found |

PROMOTING and GRAPH_COMMITTED were held by disabling `PROMOTION_PRODUCER_ENABLED` and `ACTIVATION_RECOVERY_ENABLED`, then enabling promotion (activation still paused), then enabling activation.

Final Cassandra:

```text
source_current: accepted_generation=2 current_generation=2
                active_revision_id=1a27bc31-481d-5dd2-987f-458ceae2e13a
source_versions: V1 b671fb2a... generation 1 GRAPH_COMMITTED
                 V2 1a27bc31... generation 2 GRAPH_COMMITTED
source_activation: CURRENT generation 2
```

Search transcripts: [`promoting-alpha.json`](artifacts/k8s-partial-activation-694b-2026-11-15/promoting-alpha.json),
[`promoting-beta.json`](artifacts/k8s-partial-activation-694b-2026-11-15/promoting-beta.json),
[`graphcommitted-alpha.json`](artifacts/k8s-partial-activation-694b-2026-11-15/graphcommitted-alpha.json),
[`graphcommitted-beta.json`](artifacts/k8s-partial-activation-694b-2026-11-15/graphcommitted-beta.json),
[`active-alpha.json`](artifacts/k8s-partial-activation-694b-2026-11-15/active-alpha.json),
[`active-beta.json`](artifacts/k8s-partial-activation-694b-2026-11-15/active-beta.json).
Full state/flags: [`state.txt`](artifacts/k8s-partial-activation-694b-2026-11-15/state.txt).

## Environment note

The LLM extraction plane (`vllm-extraction`) was at `0/0` after the cluster restart, and the first V1 attempt failed extraction. For this read-path acceptance, synflux was switched to `EXTRACTION_PROVIDER=regex` (a flag change only; no image deploy) so the lifecycle reached the three states deterministically. The read-path filter is independent of the extraction provider.

A first attempt also produced direct evidence for #699: its failed V1 source shared the same content-addressed ref as a later V1, and because that failed source had no current pointer, the filter's legacy fail-open kept the shared chunk visible. The clean run used uniquely salted V1 bytes so the replacement contract could be measured without that contamination. The fail-open remediation remains #699.

## What this proves

- The deployed synquest image reads `source_current.active_revision_id` per query and applies it to consumer-visible search.
- During PROMOTING and GRAPH_COMMITTED, V1 remains authoritative and V2 is hidden even though V2 is already indexed.
- After ACTIVE, the pointer flip changes the read result without reindexing: V2 appears and V1 is filtered.
- Only synquest needed deployment for the read-path change; relix/synflux/syntology images were unchanged.

## What this does not prove

- Retirement (#655): the graph/evidence retirement path is not exercised here.
- Unmapped pre-#654 chunks: the filter remains fail-open until the #699 backfill and strict switch.
- LLM extraction quality: this run used the regex provider because the LLM plane was down.
- Other read surfaces (graph/evidence/UI) are not part of this search-specific contract.
