# P3b/P4 extractor authorization proposal

**Status:** PROPOSAL ONLY — no extractor enabled, no evaluation started
**Preflight:** PR #916 / manifest `it-books-p3b-p4-preflight-v1`
**Rule:** preflight acceptance does not authorize extraction.

## Existing paths

| Path | Exists | Produces candidate entities/relations | GPU | P3a compatibility | A2 compatibility | Verdict |
|---|---|---|---|---|---|---|
| `RegexExtractorAdapter` | Yes, in-process | Identifier/Metric/Concept only; cue-based predicates | No | Direct chunk spans can be bound with P3a | Partial: cannot produce Book/Author/Chapter/Algorithm/etc. or A1 predicates reliably | Eligible only as a baseline smoke |
| `LlmExtractionAdapter` | Yes, direct OpenAI-compatible HTTP | Typed candidates with prompt recovery | Direct vLLM/TEI HTTP; not `synanton.gpu.v1` | Requires extraction-input mapping; not currently persisted | Closest semantic fit | **Not eligible as-is; gateway path deferred** |
| `ExtractionPlaneClient` / content extraction plane | Yes | Structured document text/layout, not knowledge candidates | No | Feeds document processing; not candidate extraction | No relation judgments | Not an A2 extractor |
| `LocalTikaFallbackExtractor` | Yes | Document text only | No | Not candidate extraction | No | Not an A2 extractor |
| Existing `ExtractionConsumer` pipeline | Yes | Consumes cached chunks and the selected adapter | Depends on adapter | Does not persist a P3a extraction-input→structural mapping | Can run regex but not A2-shaped | Not sufficient without an evaluation harness |

## Recommended path

**Use the existing `RegexExtractorAdapter` as a deterministic P3b/P4 baseline harness only.**

This is the only path that is presently compatible with the no-direct-vLLM/TEI constraint and does not require a new backend. It is a baseline smoke, not a claim of A1 coverage.

### Exact configuration

- Provider: `EXTRACTION_PROVIDER=regex` (the existing default).
- No `LLM_EXTRACTION_URL`, no GPU endpoint, no `synanton.gpu.v1` inference.
- Build `TextSegment` directly from each A2/P1 `STRUCTURAL_CHUNK`:
  `chunkId = P1 chunkId`, `text = chunk.text`, `charOffsetStart = 0`.
- Run `RegexExtractorAdapter.extract` in-process.
- Treat returned `PreEntity`/`RelationCandidate` offsets as `STRUCTURAL_CHUNK` offsets and bind them with `EvidenceBinder`.
- Compare only candidates the regex adapter actually emitted; do not invent types or predicates.
- Record all candidates, including rejected and unmatched ones; no fallback to document scope.

### Expected limitations

- Regex emits only `Identifier`, `Metric`, and `Concept`; A2 covers Book, Author, Chapter, Section,
  Algorithm, DataStructure, Concept, Database, DistributedSystem, Topic, Definition.
- Relation predicates are cue-derived (for example `uses`, `has`, `part of`), not guaranteed A1
  predicate names.
- Expected recall/relationship acceptance is low; ADR-022 records regex relationship F1 around
  0.000 on the earlier gold set.
- It cannot validate LLM extraction quality, prompt behavior, or GPU-path reproducibility.
- It does not provide PDF geometry; A2 is `NOT_APPLICABLE` for geometry and P1's PDF fixture must
  be used for bbox claims.
- Existing extraction consumer does not persist a P3a extraction-input mapping; the P3b/P4 harness
  must create the structural-chunk mapping explicitly.

## Deferred path

`LlmExtractionAdapter` is the semantically closest existing path, but it calls an OpenAI-compatible
vLLM/TEI endpoint directly. The current `synanton.gpu.v1` contract **does** expose `SYNTHESIZE`
(LLM generation via `/v1/chat/completions`), `RESPOND`, `EMBED`, and `RERANK`; the earlier
EMBED/RERANK-only description was stale. The deployed `gpu-gateway` is currently scaled to zero
(`0/0`), while `vllm-extraction` is running. No existing platform extraction adapter routes through
the Gateway: `LlmExtractionAdapter` uses a direct HTTP endpoint instead. A compliant LLM path would
therefore require a separately approved adapter/route using `SYNTHESIZE`, not a direct vLLM/TEI call.
This proposal does not authorize that work, and direct vLLM/TEI calls remain prohibited.

## Decision requested

Choose one:

1. **Authorize regex baseline only** for a limited P3b/P4 smoke after #916 preflight acceptance.
2. **Defer P3b/P4** until a gateway-compliant semantic extraction path is separately authorized.
3. **Reject both** and keep P3b/P4 blocked.

No option enables extraction automatically. `extractionAuthorized` remains `false` until an owner
records the chosen option and its exact configuration.
