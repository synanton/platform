# ADR-001: Classification-Aware Search & Semantic Chunking with Sub-Document Sensitivity

**Status:** Accepted  
**Date:** 2026-08-30  
**Deciders:** Architecture team  
**Design Document:** [synanton-design-1.23.md](../synanton-design-1.23.md)

## Context

The platform's security model is resource-centric (`SPACE | PROJECT | FOLDER | DOCUMENT`), which cannot express sub-document sensitivity. A single document containing identity (RESTRICTED), personal contact (PERSONAL), and financial compensation (FINANCIAL) data cannot be selectively searched by different roles without exposing restricted spans.

Design-level review identified six enforcement gaps:
1. ACL grants granularity stops at document level
2. Chunk model has no classification field for filtering
3. Cuckoo ACL pre-filter is HIGH_SECURITY-only
4. Restricted spans written to seven stores before any enforcement gate
5. PII redaction is named but never specified
6. Extraction contracts have no security surface

The platform cannot guarantee that restricted literals (e.g., SSNs) are never stored, indexed, embedded, or leaked through query-side channels.

## Decision

Accept and implement the v1.23 architecture design that introduces:

- **Classification-aware search** operating at chunk granularity
- **Original/masked representation model** for sub-document content handling (§3.2a, §3.3, §3.4)
- **Compile-time filtering** enforcement with fail-closed defaults for unlabeled content
- **Masking outcome-driven architecture** replacing whole-chunk exclusion (revised 2026-08-29)

## Consequences

**Enables:**
- Selective access to classified chunks within documents
- Enforcement of fine-grained ACLs at the chunk level
- Guaranteed exclusion of sensitive content from search indices and embeddings
- Consistent security across all seven storage layers

**Requires:**
- Implementation of chunk classification field across storage layers
- Updates to extraction and chunking contracts
- Enhancement of ACL evaluation to chunk granularity
- Masking layer implementation in search and synthesis paths

**Trade-offs:**
- Increased complexity in chunk lifecycle management
- Additional storage overhead for masked representations
- Revised design from whole-chunk exclusion to representation model (2026-08-29 update)

## Implementation Status

Implementation in progress as of 2026-08-28.

See [classification-aware-search implementation plan](../implementation/classification-aware-search/INDEX.md) and [classification-aware semantic search demo](../demos/classification-aware-semantic-search-demo.md).
