# SCEP Chunking A1–A3 Decision Record

**Status:** APPROVED — implemented in #972, merged at `7af7ff98`.<br>
**Specification:** #969, merged at `154a4caf`.<br>
**Governance record:** #970, merged at `2e23d4d2`.<br>
**Implementation:** #972, merged at `7af7ff98`.<br>
**Run evidence:** frozen run-v2 under `evaluation/it-books/synthetic-ingestion-v1/results/run-v2/`.<br>
**Upstream:** `content_extractor` issues #33 and #34; A4 remains #32.

## A1 — Canonical offset ownership

### Evidence

- P1 defines document/chunk offsets as UTF-16 units into canonical structural text
  (`docs/implementation/it-books/p1-structural-contract.md:39`).
- Frozen gold offsets validate against P1 structural text:
  `SyntheticFixtureValidationTest.goldManifestMatchesP1StructuralTextAndEvidence`.
- SCEP already has `CanonicalTextIdentity` and `CanonicalTextRange` fields
  (`extraction_payload.proto:360-382`), including UTF-16 unit and NFC/LF semantics.
- SCEP Markdown extraction currently populates original-source UTF-8 byte spans only;
  run-v2 had `canonical_range_count=0`.

### Approved decision

**SCEP owns canonical-text generation and identity. Platform consumes it.**

Contract:

1. SCEP emits one lossless canonical-text projection:
   - `projection_id`: `canonical-text`
   - `format`: `text/plain; charset=utf-8`
   - `contract_id`: `synanton.canonical-text`
   - `contract_version`: `1`
   - `outcome`: `PROJECTION_OUTCOME_SUCCESS_LOSSLESS`
   - `digest`: `sha256:` followed by 64 lowercase hex characters.
2. `SUCCESS_LOSSLESS` means no canonical content is omitted. It does **not** mean byte-for-byte
   identity with the original source: NFC normalization and LF line endings are intentional
   transformations.
3. `canonical_text_digest` equals the projection digest, using the same `sha256:<hex>` form.
   Bare hexadecimal digests are not used for canonical evidence; the source descriptor remains a
   separate identity surface.
4. `canonical_text_id` is deterministic: `ct-v1:<canonical_text_digest>`
   (for example `ct-v1:sha256:<hex>`).
5. Every element with canonical evidence carries the same identity fields:
   - source document/digest/version
   - extraction execution ID
   - normalization profile/version
   - projection contract ID/version
   - NFC normalization and LF line endings
6. Ranges are half-open UTF-16 code-unit offsets into the canonical projection.
7. Platform verifies: projection exists exactly once, digest matches, identity matches the
   source descriptor, and every accepted range is in-bounds and half-open.
8. Platform never translates original-source byte spans into canonical offsets and never
   reparses source text to manufacture a range.

Illustrative payload shape:

```json
{
  "projections": [{
    "projection_id": "canonical-text",
    "format": "text/plain; charset=utf-8",
    "contract_id": "synanton.canonical-text",
    "contract_version": "1",
    "outcome": "PROJECTION_OUTCOME_SUCCESS_LOSSLESS",
    "digest": "sha256:..."
  }],
  "elements": [{
    "id": "e1",
    "type": "ELEMENT_HEADING",
    "canonical_text_range": {
      "identity": {
        "canonical_text_id": "ct-v1:sha256:...",
        "canonical_text_digest": "sha256:...",
        "encoding": "UTF-8",
        "source_document_id": "synthetic-distributed-systems-primer-v1",
        "source_digest": "db4f2e99...",
        "source_version": "v1",
        "extraction_execution_id": "exec-1",
        "normalization_profile": "p1-markdown",
        "normalization_version": "1",
        "projection_contract_id": "synanton.canonical-text",
        "projection_contract_version": "1"
      },
      "unit": "OFFSET_UNIT_UTF16_CODE_UNIT",
      "start": 0,
      "end": 12
    }
  }]
}
```

### Failure semantics

| Condition | Result |
|---|---|
| No canonical projection | Offset unavailable; no fallback to source bytes |
| Projection digest mismatch | Reject canonical evidence |
| Missing identity | Offset unavailable; explicit reason |
| Identity/source mismatch | Reject canonical evidence |
| Invalid/out-of-bounds/reversed range | Reject range |
| Consumer uses source span as canonical | Contract violation |

### Required upstream issue

`content_extractor` #33 — supply canonical text identity/ranges.

## A2 — Metadata preamble

### Evidence

- P1 canonical text includes the Markdown metadata preamble and comments; the frozen gold validates
  against P1 text.
- SCEP Markdown extraction skips metadata lines and HTML comments
  (`TextModalityAdapter.java:149-172`).
- Run-v2 payload metadata map contains Author/Edition/Document ID, but no canonical preamble
  element or range.
- Gold `book` chunk includes the preamble; `preface` starts after it.

### Approved decision

**Recommendation: include the preamble in the canonical text projection, while keeping metadata
non-semantic.**

- The canonical projection includes the preamble and comments because the frozen benchmark
  canonical text includes them.
- Metadata does not become semantic elements and must not produce mentions.
- The `book` container chunk begins at the **start of the canonical document**, including the
  preamble, and ends at the next top-level structural boundary according to the P1 canonical
  contract. It does not begin at the first heading immediately before the body.
- The chunk text is the canonical projection slice `[0, nextTopLevelBoundary)`. The chunker
  consumes that slice; it must not inject metadata from the original Markdown or reconstruct it.
- Negative assertions remain unchanged: metadata text may be chunk content but must never become
  a mention or semantic relation.

Alternative: exclude the preamble through a separately reviewed, versioned benchmark contract.
That would require a new gold version and is not proposed for this milestone.

### Impact

| Area | Impact |
|---|---|
| Chunk boundaries | Book container starts at canonical document offset 0 and includes the preamble; section boundaries unchanged |
| Text comparison | Canonical projection contains metadata text; metadata labels remain non-mentions |
| Regression | Exact book chunk text must equal the canonical slice and include the preamble |
| Offsets | P1 canonical offsets remain valid |
| Gold | No change under the recommended option |

### Required regression

- The exact `book` chunk text must equal the canonical projection slice `[0, nextTopLevelBoundary)`.
- The text must include the frozen preamble: `**Author:**`, `**Edition:**`, `**Document ID:**`,
  and the metadata comments.
- Those metadata surfaces must not become mentions or semantic relations.
- The frozen gold is read-only.

### Required upstream issue

`content_extractor` #34 — include metadata preamble in canonical evidence if approved.

## A3 — Discontiguous evidence

### Evidence

- Current consumers use one `documentOffsetStart` / `documentOffsetEnd`.
- No current consumer requires lossless multi-span chunk evidence.
- GraphWriter derives mention offsets from the single chunk start; it cannot represent a list of
  spans.
- P1/gold section spans are contiguous. Table source spans are contiguous even though row/cell
  elements are nested.

### Approved decision

1. Emit one half-open canonical range only when the complete chunk is contiguous in canonical text.
2. Operationally, the canonical ranges of the heading and all included content must union into one
   continuous interval after bridging gaps that contain only canonical whitespace. A gap is
   bridgeable only when every code point in it has the Unicode `White_Space` property
   (`UnicodeData.txt` / UAX #44). This is a Unicode code-point test, not a language-specific
   `isWhitespace` shortcut.
   - `v1` canonical text uses LF line endings. LF (`U+000A`) remains bridgeable; VT (`U+000B`),
     FF (`U+000C`), CR (`U+000D`), NEL (`U+0085`), LS (`U+2028`), and PS (`U+2029`) are invalid
     canonical evidence and are not bridgeable whitespace.
   - Any gap containing an excluded non-whitespace code point is discontiguous.
   - Bridging does not extend into excluded semantic content or alter the chunk text.
3. For a TABLE chunk, use the TABLE element's declared canonical range. Nested TABLE_ROW/TABLE_CELL
   ranges must lie inside it. Never derive the table range from the minimum/maximum descendant
   offsets.
4. If the TABLE has no canonical range but descendants do, the table is unmapped; do not infer a
   range.
5. For discontiguous chunks:
   - emit `documentOffsetStart=-1`, `documentOffsetEnd=-1`;
   - retain contributing element IDs;
   - record `DISCONTIGUOUS_SOURCE_EVIDENCE` in `SemanticChunk.metadata` and in the evaluation
     artifacts.
6. Never synthesize a min/max range.
7. Defer multi-span storage until a real consumer requires it. Any future addition must be
   additive and separately approved.
8. Missing/invalid evidence remains explicit unmapped, never approximate or guessed.

### Diagnostic storage caveat

`SemanticChunk.metadata` can carry `evidence_status=DISCONTIGUOUS_SOURCE_EVIDENCE` in-process, and
run artifacts can preserve it. Persisted `ChunkRow` has no metadata field today, so production
persistence only retains `-1/-1` plus `sourceElements`; it cannot distinguish discontiguous from
missing/invalid without an additive evidence contract. This milestone does not add that field.
If runtime persistence must preserve the distinction, that is a separate approved contract change;
otherwise the distinction is retained in evaluation evidence only. This limitation must not be
hidden by inventing a range.

### Derived tests

| Case | Expected |
|---|---|
| Heading + body contiguous | One range |
| Table + nested cells with contiguous table span | One table range |
| Two included spans separated by permitted canonical whitespace | one bridged range |
| Two included spans separated by excluded non-whitespace | `-1/-1`, element IDs, discontiguous reason |
| Gap containing a non-LF line ending in canonical v1 | invalid canonical evidence, not bridged |
| Table parent range plus nested row/cell ranges | uses parent TABLE range, never min/max |
| Missing canonical identity | `-1/-1`, missing-evidence reason |
| No consumer for multi-span | No span-list schema change |

## Approval status

| Gate | Decision | Owner | Status |
|---|---|---|---|
| A1 | SCEP owns canonical projection/identity/ranges; Platform consumes UTF-16 canonical ranges | Platform + Content Extractor | APPROVED |
| A2 | Include metadata preamble in canonical projection; keep metadata non-semantic | Benchmark owner + Content Extractor | APPROVED |
| A3 | Single contiguous range only; no min/max; defer span lists | Platform chunking owner | APPROVED |
| A4 | `schema_digest` population | Content Extractor #32 | SEPARATE, NON-BLOCKING |

A1–A3 were approved and implemented in #972 (`7af7ff98`). The implementation remains gated on
`content_extractor` #33 and #34 for live canonical offsets and preamble evidence; A4 remains
independent and non-blocking.

## Implementation reference

- PR #972 — `feat: implement SCEP benchmark chunking contract`, merged at `7af7ff98`.
- `CanonicalTextEvidence` validates the A1 canonical projection, identity, digest, UTF-8 encoding,
  source identity, normalization, and per-element range consistency.
- `SemanticChunker` enforces the A2 document-root container range `[0, nextTopLevelBoundary)` and
  consumes the canonical slice without reconstructing preamble text.
- `UnicodeWhiteSpace` applies the Unicode 15.1.0 UAX #44 `White_Space` property for A3 gap
  bridging and rejects all non-LF line-ending forms.

## Linked upstream work

- `content_extractor` #33 — canonical text identity and UTF-16 ranges (A1).
- `content_extractor` #34 — metadata preamble canonical evidence (A2).
- `content_extractor` #32 — `schema_digest` (A4, independent).

## Resolved questions

1. Content Extractor owns the canonical-text projection, stable identity, and UTF-16 ranges (A1).
2. The `ct-v1:sha256:<digest>` identity format is approved and implemented.
3. The benchmark owner approved including the metadata preamble in canonical text without changing
   frozen gold; upstream evidence remains tracked in `content_extractor` #34.
4. `-1/-1` plus contributing element IDs is sufficient for this milestone; lossless multi-span
   storage remains deferred until a real consumer requires it.
5. Inline canonical projection content is used for this milestone; transport/storage by reference
   for large documents remains future work.
6. Canonical-text normalization is validated as NFC with LF line endings and matches P1 for the
   supported benchmark format.
