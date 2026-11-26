# A9.1 follow-up — HTML comment boundary adjudication

**Status:** Decision for review; A9.2 not started
**Inputs:** unchanged A2 fixture and immutable A4.1 candidate export
**Artifact:** `evaluation/it-books/a9-v1/comment-adjudication.json`

## Finding

The A4.1 candidates contain 30 spans inside HTML comments. They are source/licence/version,
attribution, or diagnostic-probe metadata, not rendered domain prose.

The P1 Markdown adapter treats comment lines as paragraph text; the A2 validation test confirms
that `m-cn-book` and `m-cn-topic` both point to the same comment-derived element. The rendered H1
at offset 252 is a different span.

Two A2 mentions therefore live in metadata comments:

- `m-cn-book` — `Computer Networks: A Systems Approach` (57:94)
- `m-cn-topic` — `Computer Networks` (57:74)

A2 is unchanged. These annotations are an evaluation limitation, not permission to extract
metadata as knowledge.

## Decision: classify comments, do not blanket-suppress

| Comment class | Policy |
|---|---|
| `METADATA_COMMENT` | Suppress candidate extraction; preserve offsets |
| `DOMAIN_BEARING_COMMENT` | Review and preserve if it carries unrendered domain content; none observed |
| `UNKNOWN_COMMENT` | Do not silently suppress; flag for review |

**Observed:** 30 metadata comments, 0 domain-bearing comments, 0 unknown comments.

## Other comment candidates

All 30 comment-contained candidates are metadata fragments, including SPDX/licence identifiers,
source paths, edition/version strings, author attribution, `Derived`, `PDF`, and the UTF-16 probe.
No additional meaningful unrendered domain fact was found.

## Expected impact on A9 evaluation

- Metadata-comment filtering suppresses **30/70 A4.1 candidates (42.9%)**.
- A2 recall denominator remains 22 under the unchanged fixture.
- `m-cn-book` and `m-cn-topic` are A2 metadata-span annotations; if A9 filters comments, raw A2
  matching will show those as unavailable. Report this explicitly rather than editing A2 or
  treating the metadata as extraction-positive.
- Do not claim a recall improvement or regression from these two annotations without separating
  the fixture limitation.
- A4/A4.1 metrics and artifacts remain unchanged.

## A9.2 implications

1. Implement comment classification before candidate selection, preserving original offsets.
2. Use explicit metadata patterns, not broad keyword deletion.
3. Extract rendered headings with full spans and conservative semantic cues.
4. Type only with explicit textual/structural evidence.
5. Emit relations only when the predicate is in the pinned A1 release and textual evidence
   supports it; record omission reasons.
6. Evaluate raw A2 metrics plus a clearly labeled metadata-span sensitivity note.

No extraction code, A2, ontology, A4/A4.1, A8/A8.1, graph, or evaluation gate was changed.
