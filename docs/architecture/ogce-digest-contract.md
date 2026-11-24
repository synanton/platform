---
title: "OGCE Digest Contract (schema and prompt provenance)"
status: "current"
last_reviewed: "2026-11-23"
---

# OGCE Digest Contract (schema and prompt provenance)

**Tracking:** [#831](https://github.com/synanton/platform/issues/831)
**Decision context:** [ADR-044](./decisions/adr-044-ontology-grounded-chunk-extraction.md)
**Consumers:** [#829](https://github.com/synanton/platform/issues/829) prompt digest, [#827](https://github.com/synanton/platform/issues/827) schema compiler contract, [#832](https://github.com/synanton/platform/issues/832) schema compiler implementation.
**Status:** contract for implementation. No runtime behavior is claimed by this document.

## Purpose

Schema and prompt digests are both inputs to the extraction cache key and both appear in extraction-result provenance. They must use one algorithm, one canonical-form discipline, one storage shape, and one null/legacy rule. This document is the shared contract; #829 and #832 consume it and must not define parallel conventions.

## Algorithm

- **SHA-256** over UTF-8 bytes, rendered as lowercase hexadecimal (64 characters).
- No salting, no truncation, no alternate encoding.
- The digest is of content, not of a version label. A version label may change; the digest is authoritative.

## Prompt canonical form

- The digest is computed over the **exact rendered prompt bytes sent to the model**: system prompt, few-shot examples, and any tokenizer/model-specific text that changes those bytes.
- The canonical form must be byte-identical to the rendered prompt. If canonicalization and rendering can diverge, the contract is broken.
- Compute the digest at **prompt-compile time** so it is available as a cache key before the call.
- Immediately before the call, re-hash the rendered prompt and compare. On mismatch, fail closed with `PROMPT_DIGEST_MISMATCH`; do not read or write a cache entry under the wrong digest.
- Line endings are LF. No trailing-whitespace normalization is applied unless it is part of the rendered bytes.

## Schema canonical form

- The digest is computed over deterministic UTF-8 JSON of the compiled schema artifact: ontology release, classes, aliases, properties, domain/range, and any required validation metadata.
- JSON object keys are sorted lexicographically; arrays keep their defined order; no timestamps, environment paths, or run-specific values are included.
- Same ontology release and compiler version -> same digest. A release/compiler change -> a new digest.
- The compiler emits the canonical artifact; the digest is computed from those exact bytes.

## Ontology release digest (Phase 1 extension)

This is an additive extension for ontology release identity. It does not change prompt or schema digest semantics.

- `release_id` is the immutable ontology version UUID rendered as a string. It is the release identity; `version`/label remain mutable display names.
- `release_digest` is SHA-256 of a canonical release manifest, not of a single ambiguous concatenation.
- Manifest form, LF line endings, UTF-8, keys sorted lexicographically:

```text
ontology-release-v1
format=<turtle|hcl>
component.<name>=sha256:<lowercase hex>
```

- Component inputs:
  - `turtle` format: `content` = exact uploaded or seeded Turtle bytes.
  - `hcl` format: `tbox` = generated TBox Turtle bytes, `shapes` = generated SHACL Turtle bytes, `schema_ir` = canonical pretty-printed schema IR JSON bytes.
- Component hashes are inputs to `release_digest`; they are not separate API fields in this phase.
- Null semantics: legacy rows may have null `release_id`/`release_digest`; no backfill. New writes must produce both. Null means historical/unverified, never “same as current.”
- A canonicalization change requires a new manifest version, not a silent digest change.

## Storage and cache identity

Extraction results carry:

| Field | Notes |
|---|---|
| `prompt_version` | Human-readable label; mutable. |
| `prompt_digest` | SHA-256 of the rendered prompt; authoritative. |
| `schema_version` | Human-readable label; mutable. |
| `schema_digest` | SHA-256 of the canonical schema artifact; authoritative. |

The extraction cache key includes both digests plus the execution inputs:

```text
(tenant_id, source_version_id, segment_hash,
 prompt_digest, schema_digest,
 model_id, model_revision,
 temperature, top_p, tokenizer_version)
```

`extraction_generation` is a record identifier and is **not** a cache input.

## Null and legacy handling

- A null prompt or schema digest means **historical/unverified**, not "same as current."
- A historical record may be replayed only after verifying the current canonical digest against a recorded/known artifact. If the text changed, mark the record non-replayable.
- Do not backfill null digests by hashing today's files; that would manufacture provenance.

## Mismatch policy

- Prompt rendering/digest mismatch: fail closed (`PROMPT_DIGEST_MISMATCH`), no cache read/write.
- Schema artifact/digest mismatch: fail closed (`SCHEMA_DIGEST_MISMATCH`), no extraction under the mismatched schema.
- Version labels are not accepted as proof. A label change without a digest change is a bug; a digest change with the same label is a new content identity.

## Test vectors

Literal UTF-8 bytes; newline is LF.

| Content | SHA-256 |
|---|---|
| `You extract JSONL.\nReturn one object per line.` | `5bce5985be0a04b98f3ea0977748db3205181028cf1498b27cbd7ecbc078c863` |
| `{"classes":["Person"],"properties":{},"relations":{}}` | `7a7ac81f184a116e4120d7f27163403ae75b22944c813e7cd9e4480d18d7ebb0` |

#829 and #832 must include tests using these vectors or a superseding reviewed vector set.

## Boundaries

- No F1, extraction-quality, gate, profile, or ADR-044 transition claim.
- No prompt tuning or ontology-subset decision.
- No runtime implementation in this document; #829/#832 implement against it.
