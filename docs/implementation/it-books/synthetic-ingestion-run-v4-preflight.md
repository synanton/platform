# Synthetic Ingestion Run v4 Preflight

**Status:** Prepared; execution not authorized  
**Tracker:** [#974](https://github.com/synanton/platform/issues/974)  
**Parent:** [#936 — Synthetic Ingestion Benchmark v1](https://github.com/synanton/platform/issues/936)  
**Baseline:** `main` at `1e048d5b`; merged benchmark implementation #972 at `7af7ff98`  
**Current benchmark evidence:** immutable run-v3 under `evaluation/it-books/synthetic-ingestion-v1/results/run-v3/`

## Gate

Do not start run-v4 until all required upstream gates are merged and verified against a real fixture
payload:

- [ ] `content_extractor` #33 — canonical text projection, identity, and UTF-16 element ranges.
- [ ] `content_extractor` #34 — approved metadata-preamble canonical evidence.
- [ ] #33/#34 payload contracts verified directly from a real fixture payload.
- [ ] #32 (`schema_digest`) state recorded; it is independent and non-blocking unless the benchmark
      owner explicitly changes the gate.

If any required gate is missing or inconsistent, stop before ingestion. Do not substitute source
bytes, reparse text, or synthesize evidence.

## Preflight checklist

### 1. Platform baseline

- [ ] Record current `main` SHA and confirm #972 is an ancestor.
- [ ] Confirm the opt-in `semantic-benchmark` strategy is active for this run.
- [ ] Confirm production retrieval defaults are unchanged.
- [ ] Confirm no production code change is included in the run PR.
- [ ] Confirm production extraction remains SCEP-only: no local Tika, PDFBox, Markdown parsing, or
      `DocumentStructureService` invocation.

### 2. Immutable inputs

- [ ] Verify the byte-identical frozen source under
      `evaluation/it-books/synthetic-ingestion-v1/source/`.
- [ ] Verify the frozen gold manifest and its checksums.
- [ ] Verify the pinned IT Books ontology release and checksum.
- [ ] Record source, gold, ontology, scoring, and denominator identities in the run manifest.
- [ ] Do not edit fixture, gold, ontology, scoring, or denominators.

### 3. Runtime identity

- [ ] Record Platform and Content Extractor image digests.
- [ ] Record deployment/configuration identities used by the isolated local-real-demo path.
- [ ] Confirm SCEP reachability through the production wiring.
- [ ] Confirm the run uses the existing isolated local-real-demo ingestion path, not a parallel
      runner or bypass.

### 4. Canonical evidence contract

For at least one real fixture payload, verify:

- [ ] Projection ID `canonical-text`, contract `synanton.canonical-text`, version `1`.
- [ ] Format `text/plain; charset=utf-8`, outcome `PROJECTION_OUTCOME_SUCCESS_LOSSLESS`.
- [ ] `sha256:<64 lowercase hex>` digest matches the inline canonical bytes.
- [ ] `canonical_text_id == ct-v1:<canonical_text_digest>`.
- [ ] UTF-8 decoding succeeds and identity encoding is `UTF-8`.
- [ ] Source document/digest/version and execution identity match the payload.
- [ ] Normalization profile/version are present and consistent across elements.
- [ ] NFC normalization, LF line endings, UTF-16 code-unit unit, and half-open bounds are valid.
- [ ] Metadata preamble is present only through producer-supplied canonical text/evidence.
- [ ] Missing, mixed, invalid, or out-of-bounds evidence fails closed.

### 5. Run artifacts

Produce a new immutable result set under:

`evaluation/it-books/synthetic-ingestion-v1/results/run-v4/`

- [ ] `manifest.json` — source/gold/ontology/image/config identities and checksums.
- [ ] `report.md` — stage-by-stage report and run-v3 comparison.
- [ ] `stage-results.json` — explicit `PASS`, `PARTIAL`, `FAIL`, `NOT_RUN`, or `CONDITIONAL` with
      denominators and artifact references.
- [ ] `extraction-evidence.json` — canonical projection/range evidence captured from the run.
- [ ] `chunk-comparison.json` — 14/14 section-path coverage, container headings, and atomic table
      behavior.
- [ ] `negative-assertions.json` — metadata non-semantic checks and other prohibited inferences.
- [ ] `raw/` — raw stage captures needed for reproducibility.
- [ ] `SHA256SUMS` — checksums for every artifact.

### 6. Required comparison with run-v3

- [ ] Canonical offsets are populated from verified SCEP ranges, not `-1` fallbacks.
- [ ] Book-container range starts at canonical offset zero and includes the metadata preamble.
- [ ] 14/14 section paths and expected container headings are preserved.
- [ ] Appendix A remains one atomic TABLE chunk with no duplicate row/cell chunk.
- [ ] Metadata remains non-semantic and creates no mentions or relations.
- [ ] Downstream stages report explicit status with denominators: source evidence, mentions,
      typing, resolution, relations, ontology validation, and evidence binding.
- [ ] Retrieval remains `CONDITIONAL` unless the existing harness supports it.
- [ ] Graph projection remains disabled unless existing isolated authorization is explicitly
      available.

## Stop conditions

Stop and report rather than run if:

- #33 or #34 is not merged or its real payload contract is not verified;
- canonical identity, digest, source identity, normalization, or ranges are inconsistent;
- any step requires source-byte translation, text search, reparsing, min/max synthesis, or guessed
  evidence;
- the run would require editing the fixture, gold, ontology, scoring, or denominators;
- the run would require reviving deferred production source-span integration (#954) or bundling a
  production fix.

## Boundaries

- No production code, persistence schema, evidence-consumer, ontology, fixture, gold, baseline,
  scoring, or denominator changes in the run task.
- No graph writes, semantic-model/GPU load, or authorization expansion.
- Keep run-v3 immutable; run-v4 must be a new immutable result set.
- One coherent PR contains the manifest, artifacts, and report. Do not auto-merge.
