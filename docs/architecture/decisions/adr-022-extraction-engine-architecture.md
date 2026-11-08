# ADR-022: Pluggable Knowledge Extraction Pipeline (RAKG)

**Status:** Accepted 2026-11-08 (Phase B adapter landed 2026-11-08)
**Basis:** The deterministic regex extractor scored entity F1 0.182 on the frozen 5-document gold and 0.000 relationship F1; a tuned Qwen3-4B prompt reached entity F1 0.618 / relationship F1 0.076, and the Java adapter reproduced 0.618 / 0.078 on the live cluster endpoint. The model must run out-of-process on the GPU plane, and regex must remain a working fallback when the GPU is down, so the ingestion pipeline cannot hardcode vLLM.

## Decision

1. **Port for extraction.** All engines implement `org.synanton.extraction.engine.ExtractionEngineAdapter`, consuming a `TextSegment` + `ExtractionContext` and returning candidate `PreEntity`/`RelationCandidate` lists. Resolution, validation, and graph commit stay downstream; the contract never exposes engine-specific types.
2. **Two engines behind the port.** `RegexExtractorAdapter` is the deterministic baseline and default. `LlmExtractionAdapter` (RAKG Phase B) calls an OpenAI-compatible vLLM endpoint out-of-process (`qwen3-extraction`, Qwen3-4B-Instruct-2507) and parses the JSON response into the same contract. Selection is `EXTRACTION_PROVIDER=regex|llm`, default `regex`; `LLM_EXTRACTION_URL` and the model id are configuration, not code.
3. **Sentence-aligned input.** Chunks come from the shared `SentenceChunker` (`strategy=sentence`: whole sentences, ≤512 tokens, one-sentence overlap). The adapter may also run over any `TextSegment`; the evaluator and synflux use the same chunker so offsets and context match.
4. **Offset recovery, not LLM counting.** LLMs return usable surface forms but unreliable character offsets. The adapter locates each `surfaceForm` in the segment (`indexOf` with a monotonic cursor, falling back to a broader search) and derives `charStart`/`charEnd`; unanchorable coreferences are dropped rather than assigned a fabricated span. The prompt still requests offsets, which are used only as hints.
5. **Synthetic endpoints.** When a returned relation references a form absent from `preEntities`, the adapter adds a synthetic `PreEntity` at the recovered span with a default type (`UNKNOWN`, or a predicate-derived hint). Relations are deduplicated by `(subject, predicate, object, evidence span)`; evidence quotes are anchored when present, otherwise the endpoint union is used.
6. **Versioned prompt.** The tuned system prompt lives in `src/main/resources/prompts/extraction-v1.txt`, versioned independently of the adapter code, so a prompt change is a reviewable artifact and the gold run is reproducible. The prompt lists the documented entity types and the allowed predicate vocabulary; unknown predicates are normalized to lower_snake_case.
7. **Measurable against frozen gold.** `evaluation/gold/` (5 documents, 70 mentions, 44 relationships, `MANIFEST.sha256`) is the acceptance artifact. The regex baseline, the prompt-tuning harness, and the Java adapter are scored by the same overlap/exact/type/relationship/evidence metrics in `evaluation/eval_extraction.py`.

Consequences:
- **Engines:** a third engine (local model, hosted API, or a different provider) is a new adapter, not an ingestion rewrite.
- **Availability:** `EXTRACTION_PROVIDER=regex` remains the safe default; the LLM path needs the GPU plane and a reachable endpoint, and fails closed per document without blocking the deterministic path.
- **Latency:** the LLM adapter adds a synchronous HTTP call per chunk; batch/concurrency tuning is deferred, and extraction is a candidate stage rather than a hard prerequisite for search projections.
- **Correctness:** surface-form recovery fixes the dominant 4B failure mode but cannot anchor pronouns/aliases; coreference and retrieval-assisted resolution remain future phases (RAKG #560).
- **Measurability:** the frozen gold and prompt version make regressions visible; the Java adapter reproduces **0.618 entity / 0.078 relationship F1** (type 0.697, 33 relation candidates) against the live cluster model.
- **Known gap:** relationship F1 is still driven by docs 1–2; the Skiena docs have predicate/endpoint misalignment (implicit algorithmic relations), to be attacked by prompt v2 and resolution, not by adapter heuristics.
- **Rejected:** embedding vLLM calls in the ingestion pipeline with no port, and making the LLM the only extractor. Both would couple availability and reproducibility to a single GPU service and lose the deterministic fallback.

## Context

The ingestion pipeline previously had only a narrow keyword rule (payment terms), which scored 0.000 on the extraction gold, and a structured-document extraction stage that produces text, not typed knowledge. The RAKG work needs typed mentions, relationships, evidence, and eventually resolution and SHACL validation. The 4B model produced strong candidate entities but could not count characters, invented predicates, and omitted relation endpoints; those are precisely the responsibilities of an adapter, not the model. The port keeps the knowledge-construction pipeline swappable and measurable while the GPU plane evolves independently.
