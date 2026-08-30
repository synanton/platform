# How Synanton Works

### A guide to ingestion and security processing

---

## About this book

This is a narrative companion to the engineering references in `docs/architecture/`, `docs/implementation/`, and `docs/demos/`. Those documents are the source of truth — precise, versioned, and normative. This book exists to explain *why* the system is shaped the way it is, in prose, with diagrams, for a reader who wants to understand Synanton before diving into a specific module's contract.

It focuses on two things the platform spends most of its engineering effort on: **getting a document from a raw source into searchable knowledge** (ingestion), and **making sure that knowledge never exposes more than a caller is entitled to see** (security). Query planning, GraphRAG synthesis, cost attribution, and the GPU execution plane are covered only where they intersect with those two subjects.

**Status of the facts in this book.** Synanton's architecture is versioned (v1.19 → v1.23). Where this book describes something as *shipped*, it means the design doc marks it `Approved` and the module exists. Where it describes something as *planned*, it means an implementation plan exists (see `docs/implementation/`) but the code has not landed yet — as of this writing, that is true of most of the v1.23 classification and masking work. Where it describes something as *proposed*, it means a design proposal exists but has not been adopted into the approved baseline. The book calls this out explicitly at each point, because a security document that quietly overstates what is actually enforced is worse than no document at all.

**Source map.** Every chapter ends with pointers back to the canonical documents it draws from, so you can go deeper without hunting.

---

## Summary

Synanton is a multi-tenant enterprise knowledge platform. It takes documents from wherever an organization keeps them — S3, SharePoint, file systems, databases, webhooks — and turns them into three complementary, searchable representations of the same knowledge: a lexical index for exact terms, a vector index for meaning, and a knowledge graph for relationships. A query can use any of the three, or fuse all three together.

The interesting engineering problem is not building three search backends. It is doing so **safely**, when a single document can contain information at wildly different sensitivity levels — a person's identity, their salary, a paragraph of public policy — and different callers are entitled to see different slices of it, and that entitlement can change at any time without anyone re-processing the document.

Synanton's answer has three moving parts, developed across versions:

1. **Structure and meaning survive ingestion.** A document isn't flattened into an opaque blob of text; it is parsed into structured elements (headings, tables, page boxes — v1.21), and those elements are grouped into semantic chunks that respect the document's own structure (v1.22).
2. **Sensitivity is a property of the chunk, not the document.** A `classification[]` field on each chunk expresses *what kind of information this is* (`PERSONAL`, `FINANCIAL`, `RESTRICTED`, `PUBLIC`), independent of *who* may see it (v1.23).
3. **Masking and authorization are two different mechanisms.** Masking decides what representation of a chunk is safe to compute and store at all. Authorization decides, at query time, which of the available representations a given caller receives. Conflating the two — as an earlier draft of the v1.23 design did — either destroys information legitimate users need, or leaks information nobody should get.

The rest of this book walks through both halves — ingestion, then security — in the order a document actually experiences them, and then shows how the result lands in three stores that all have to agree on what "safe" means.

---

# Part I — What Synanton Is

## 1. The Platform in One Page

Synanton unifies three capabilities that are traditionally separate products: full-text retrieval, dense semantic (vector) retrieval, and knowledge-graph reasoning. It exposes them through one engine, reachable over REST/gRPC (for humans and services), MCP (for AI agents as tools), and ACP (for agent-to-agent calls).

What it does, end to end:

- **Ingests** documents from heterogeneous sources through a pluggable content-adapter interface.
- **Transforms** content through a staged pipeline — parse, chunk, enrich, embed — with a durable cache so a crash mid-pipeline never loses work or duplicates it.
- **Classifies and protects** sensitive content as part of that same pipeline, rather than as an afterthought bolted onto search.
- **Indexes** the result into a hybrid search kernel (lexical + vector) and a graph, keeping all three in lockstep on what is safe to expose.
- **Serves** queries that can combine lexical matching, semantic similarity, and graph traversal, filtered by the caller's actual entitlements at the moment they ask — not the entitlements that happened to exist when the document was ingested.

Two design commitments run through everything that follows:

> **A module has one name, one identity, everywhere** — in code, in metrics, in config keys, in dashboards. `synflux` is always `synflux`.

> **The platform never silently degrades.** If a reranker is down, if GPU capacity is saturated, if a graph connector can't natively execute a pattern — the system says so, in a response header or a metric, rather than quietly returning a worse answer.

*Source: `docs/architecture/synanton-design-1.22.md` §1–2.*

## 2. Architecture at a Glance

A simplified module map — enough to orient the rest of this book:

```
 External sources (S3, SharePoint, RDBMS, filesystems, Kafka CDC, webhooks)
        │
        ▼
   synvault        content store + tier manager (hot/warm/cold/glacier)
        │
        ▼
   synflux         ingestion pipeline: acquire → parse → chunk →
        │           classify + mask → enrich → embed → persist
        ├──────────────► Cassandra (ingestion-cache)   [write-through, synchronous]
        │
        ▼
   Kafka: synflux_enriched_chunks
        │
        ▼
   synflux-router   fans out to every downstream store, idempotently
        │
  ┌─────┼──────────────┐
  ▼                    ▼
synquest             relix
(lexical + vector)   (knowledge graph)
  │                    │
  └─────────┬──────────┘
            ▼
        planner        query planning, cost/residency awareness
            ▼
        gateway        ACL + representation injection, reranking,
            │           cross-tenant cache, anomaly streaming
  ┌─────────┼─────────────┬───────────────┐
  ▼         ▼             ▼               ▼
synapt    UI backend   MCP / ACP     synanton-mcp

topology   — authoritative store: orgs, users, groups, ACLs, class_grants, policy
security   — authentication, outbound token exchange
control-plane — admin, Temporal workflows, forecasting, anomaly detection, GitOps
```

Two modules matter enough to security and ingestion that they get their own chapters: `synflux` (everything that happens to a document before it's searchable) and `topology` + `gateway` together (everything that decides who sees what). `synquest`, `relix`, and the reverse index / vector store / graph they hold are covered in Part IV.

*Source: `docs/architecture/synanton-design-1.22.md` §4–5.*

## 3. The Life of a Document

Before the detail, the shape of the whole journey:

```
raw bytes
   │
   ▼
STRUCTURE            (what is in this document, and how is it organized?)
   │
   ▼
SEMANTIC CHUNKS      (what are the coherent, meaningful units inside it?)
   │
   ▼
CLASSIFICATION       (what kind of information does each unit contain?)
   │
   ▼
MASKING DECISION     (what representation of each unit is safe to store?)
   │
   ▼
PROTECTED CHUNKS
   │
   ├──────────────┬──────────────┐
   ▼              ▼              ▼
REVERSE INDEX   VECTOR STORE    GRAPH
   │              │              │
   └──────────────┼──────────────┘
                  ▼
             USER QUERY
                  │
                  ▼
        AUTHORIZATION (who is asking, and what may they see?)
                  │
                  ▼
          AUTHORIZED RESULT
```

Everything from "raw bytes" to "protected chunks" happens once, at ingestion, regardless of who will eventually query the document. Everything from "user query" downward happens fresh, on every query, based on whoever is asking *right now*. That separation — process once, authorize dynamically — is the single most important architectural idea in this book, and Part III explains why.

---

# Part II — Ingestion: From Source to Knowledge

## 4. Acquiring Content

Ingestion starts with `synvault`, the content-store abstraction that sits in front of S3, FileNet, SharePoint, RDBMS sources, filesystems, and Kafka CDC streams via a pluggable adapter interface. Adapters are cursor-resumable: a partial pull restarts from the last consistent point rather than re-reading everything.

`synflux` — the ingestion engine — pulls through `synvault.ContentPullPort` and writes the raw blob back through `ContentPushPort`, idempotently keyed on a content reference ID. A manifest row is created in the ingestion cache with `state = ACQUIRED`. This manifest is the spine of the whole pipeline: every later stage updates it, and if the pipeline crashes, the manifest is exactly what tells the operator (or an automated retry) where to resume.

Before any expensive work happens, a SHA-256 digest of the raw bytes is checked against `ingestion_cache_source_digests`. An exact re-ingest of a file already processed short-circuits the entire pipeline — no reparse, no re-chunk, no LLM call, no re-embed. The manifest simply re-points to the artifacts that already exist.

*Source: `docs/architecture/synanton-design-1.22.md` §6 steps 1–2, §17.*

## 5. Extraction and Structure

A raw PDF or DOCX is not text — it's a rendering of text, tables, headings, and images that happens to be recoverable. The **structured content extraction plane** (introduced in v1.21) is the part of the system whose only job is answering: *what is in this document, and how is it organized?*

The extraction contract (`synanton.extraction.v1`) is deliberately narrow. It specifies *what* comes out — a `DocumentPayload` containing normalized `elements`, `headings`, `tables`, and page bounding boxes — and says nothing about *how* it's produced. Parsers, OCR sidecars, GPUs, and worker topology are extraction-plane implementation detail; they must never leak into the contract. This matters because it lets the extraction backend evolve (swap parsers, add OCR, add a new format) without touching anything downstream.

```
Object store (raw bytes)
        │
        ▼
synanton.extraction.v1
        │
        ▼
DocumentPayload  { elements, headings, tables, page boxes }
        │
        ▼
synflux (SemanticChunkStage)
```

If the extraction plane is unavailable or declines a document type, `synflux` fails open to a local Tika-based fallback rather than blocking ingestion outright — a deliberate resilience choice, not an oversight: a broken extraction service should degrade quality, not availability.

*Source: `docs/architecture/synanton-design-1.22.md` Part IX; `docs/architecture/proposals/v1.21/Synanton_v1.21_Structured_content_extraction_plane.md`.*

## 6. Semantic Chunking

Once a document's structure is known, it has to be cut into pieces small enough to embed and index, but large enough to still mean something on their own. This is where a lot of naive RAG systems fail: split on a fixed token window and you can sever a table mid-row, or separate a heading from the paragraph it introduces.

Synanton's rule, formalized in v1.22, inverts the usual priority:

> **Semantic boundaries first. Token limits are a fallback, not the primary rule.**

The chunker (`synflux.SemanticChunkStage`) works from the structured `elements` collection produced by extraction — never from flattened text alone — and follows the document's own section hierarchy, splitting large sections at paragraph or list boundaries only when they exceed the token budget. Tables are treated as atomic: a table is never split arbitrarily, and it carries a structured, embedding-friendly projection of its own content rather than being flattened into prose.

Every chunk that comes out carries:

```
Chunk
├── chunk_id
├── section_path      e.g. ["3. GPU Execution Plane", "3.1 GPU Gateway"]
├── content
├── source_elements   provenance back to the extracted elements
├── page_start / page_end
└── (from v1.23) classification[]
```

`section_path` is what lets a search result cite "§3.1 GPU Gateway" instead of "page 14 of a 40-page PDF." `source_elements` and the page range are what let the platform prove, later, exactly where a piece of knowledge came from — a property that becomes load-bearing again in the security model (Part III) and in the graph (Part IV).

*Source: `docs/architecture/synanton-design-1.22.md` Part X; `docs/architecture/proposals/v1.22/Synanton v1.22  Structured Content Semantic Chunking Design Proposal.md`.*

## 7. Enrichment: Two-Pass LLM Analysis

Enrichment is optional and, when enabled, deliberately split into two sequential LLM passes rather than one:

- **Pass 1 — Analysis.** Given the parsed content and a snapshot of already-linked entities, the model emits structured findings: candidate entities, concepts, arguments, cross-references to existing knowledge, contradictions with what's already indexed, and a recommended structure. This is cached by the SHA-256 of the canonical text, and — importantly — kept even after Pass 2 runs, because it's independently useful for surfacing contradictions in the review UI later.
- **Pass 2 — Generation.** Given Pass 1's output, a second call produces ontology-mapped entity/relation candidates for the graph, indexing hints, and review items (new entity types, unresolved contradictions, low-confidence chunks) that route to `synreview` for human adjudication.

The reasoning for splitting rather than doing it in one call: a single LLM call cannot simultaneously parse content, reason about surrounding context, *and* produce a well-structured downstream artifact within a bounded context window without quality loss. Splitting also creates a natural checkpoint — Pass 1's output can be reviewed and reused even if Pass 2 needs to be retried.

Embedded images go through a parallel, optional vision-captioning step: each image is normalized to PNG, hashed, and checked against a caption cache before a vision model is invoked — a real cost saver for documents that repeat the same logo or chart template.

*Source: `docs/architecture/synanton-design-1.22.md` §6 step 5, §17.*

## 8. Embedding

Each chunk is turned into a vector by calling an embedding model (resolved through the platform's model-serving directory, which maps a logical model name to wherever it's actually being served — a detail this pipeline stage doesn't need to know). Embeddings are cached by the chunk's content hash; an unchanged chunk never pays for a redundant embedding call.

When the platform is in **GPU degraded mode** — the GPU cluster is saturated or unavailable — ingestion doesn't block. It falls back to a small CPU-compatible embedding model, and if even that can't keep up, it skips embedding entirely and proceeds with lexical-only indexing. Every row ingested this way is marked `embedding_quality = DEGRADED` on the manifest, and a background workflow re-embeds it once the GPU cluster recovers. The platform would rather ingest at reduced quality, visibly, than stop ingesting.

*Source: `docs/architecture/synanton-design-1.22.md` §6 step 6, §17 ("GPU degraded mode").*

## 9. The Cache-Before-Bus Invariant

This is the rule that makes the whole pipeline crash-safe:

> **The Cassandra commit must succeed before anything is published to Kafka. No exceptions.**

Every artifact the pipeline produces — parsed content, chunks, embeddings — is written through Cassandra with quorum first. Only after that commit succeeds does the enriched chunk envelope get published to the `synflux_enriched_chunks` Kafka topic (kept at a minimum 30-day retention floor). If Cassandra fails, nothing downstream ever hears about the document — there is no dangling reference for a consumer to choke on. If Kafka publish fails *after* a successful Cassandra commit, an outbox-style retry eventually gets the message out, and downstream consumers de-duplicate by an idempotency key, so a retried publish is harmless.

```
parse/chunk/embed
        │
        ▼
  Cassandra commit  ── must succeed first
        │
        ▼
  Kafka publish  (synflux_enriched_chunks)
        │
        ▼
  synflux-router
```

This ordering is why, later in this book, the security model can make a similarly absolute promise about masking: if masking is decided and applied *before* this commit, nothing that shouldn't exist downstream ever gets a chance to leak through the bus.

*Source: `docs/architecture/synanton-design-1.22.md` §6 steps 7–8, "Failure semantics."*

## 10. One Pipeline, Three Destinations

A separate service, the **Synflux Router**, consumes `synflux_enriched_chunks` and fans each chunk out to every downstream store — today, `synquest` (lexical + vector) and `relix` (graph). Each dispatch carries an idempotency key derived from `sha256(content_ref_id || chunk_index || mutation_op)`, so a router crash mid-dispatch (it resumes from the last committed Kafka offset) never produces a duplicate write.

Deletion uses the same channel: if a document is erased, the router emits a `TOMBSTONE` envelope instead of a chunk envelope, and every downstream target deletes rather than upserts. The router also watches a 15-minutes-ahead lag forecast from the control plane and increases its fetch parallelism *before* the Kafka retention window is threatened — a small piece of the platform's broader "adaptive, not reactive" scaling philosophy.

The manifest tracks per-target dispatch state (`dispatched_to_synquest_at`, `dispatched_to_relix_at`), which is what makes a stuck or lagging target recoverable: an operator can replay missing dispatches straight from the manifest without re-ingesting anything.

This is also the exact point where ingestion and security processing meet: the chunk that the router fans out is not the chunk that came out of semantic chunking — it has already been through classification and, where necessary, masking, which is where Part III picks up.

*Source: `docs/architecture/synanton-design-1.22.md` §6 steps 9–12, §17.*

---

# Part III — Security: Classification, Masking, and Authorization

## 11. Why Resource ACLs Aren't Enough

Up through v1.22, Synanton's security model was **resource-centric**: access control lived at the level of `SPACE | PROJECT | FOLDER | DOCUMENT`. That works as long as sensitivity is uniform within a document. It breaks the moment it isn't — and enterprise documents routinely aren't. A single employee-record PDF might contain an identity section (should essentially never be searchable in bulk), a contact section (HR should see it, most people shouldn't), and a compensation table (payroll should see it, HR shouldn't).

A design-level security review of the platform identified six concrete enforcement gaps that a resource-only model can't close:

| Gap | Consequence |
|---|---|
| ACL grants stop at `DOCUMENT` granularity | Can't grant HR access to personal-data sections while denying financial sections in the *same* document |
| The chunk model has no `classification` field | There's nothing to filter on, even if you wanted to |
| The fast ACL pre-filter was `HIGH_SECURITY`-only | `STANDARD` tenants relied on a post-filter, which leaks term statistics and hit counts even when it correctly hides the actual hit |
| Restricted content reached **seven stores** before any gate ran | Raw storage, chunk cache, analysis cache, embedding cache, Kafka (30-day retention), search index, graph, synthesis cache, anomaly topic — a literal like an SSN had seven separate places to leak from |
| "PII redaction" was named in the design but never specified | No detector, no policy, no contract — a placeholder, not a control |
| Extraction contracts had no security surface at all | The only mention of sanitization anywhere was a single optional field in a proof-of-concept |

v1.23 exists to close these gaps, at **chunk granularity**, with **compile-time** enforcement (not a filter bolted on after the fact), and a **fail-closed** default for anything that hasn't been explicitly classified.

*Source: `docs/architecture/synanton-design-1.23.md` §1.*

## 12. Classification: Naming What the Information Is

The first move is to separate two things that are easy to conflate: *what kind of information this is*, and *who may see it*.

**Classification** answers the first question. Every chunk gets a `classification[]` field — `PUBLIC`, `PERSONAL`, `FINANCIAL`, or `RESTRICTED` — assigned by a new `ClassificationDetector` stage that runs inside `synflux`, after chunking and before any commit to Cassandra. The detectors are deliberately **deterministic and auditable**, not LLM-based: regex plus a Luhn check for SSNs, regex for US phone numbers, regex plus a gazetteer for addresses, and a fixed vocabulary of table headers (`"Gross income"`, `"Federal tax"`, `"Salary"`) for financial content. Low-confidence spans are routed to `synreview` for human adjudication rather than guessed at.

**Authorization** answers the second question, separately, through a `class_grants` table that maps a subject (user, group, or role) to a class they may search or view — `PAYROLL` grants `FINANCIAL`, `HR` grants `PERSONAL`, and so on, independent of resource ACLs. Effective visibility is the intersection:

```
effective visibility = resource_acl  ∧  class_grants
```

Keeping these as two separate axes is what makes the security-group mapping changeable without touching any stored chunk: if `AUDIT` is later also granted `FINANCIAL`, nothing about the classified content changes — only the query-time filter changes what it admits. Chapter 18 comes back to why that matters operationally.

*Source: `docs/architecture/synanton-design-1.23.md` §3.1–3.2.*

## 13. The Masking Decision: Single, Dual, or Masked-Only

Classification alone is not sufficient. Knowing a chunk is `FINANCIAL` doesn't stop the literal salary figure inside it from being copied into a search index, an embedding, and a graph fact. That's what **masking** is for, and it is a genuinely different operation from classification:

| Operation | Question it answers |
|---|---|
| Detection | Is sensitive information present in this specific chunk? |
| Classification | What sensitivity class does this chunk belong to? |
| Masking | Which literal spans must not be propagated in the clear? |
| Authorization | Who may access this classification? |
| Representation selection | Which stored form does *this* caller actually receive? |

The masking stage always computes a masked form of a chunk's content and compares it against the original. What happens next depends on two things: did masking actually *change* anything, and does policy allow an original to exist at all for the matched class.

```
detector match
      │
      ▼
apply masking policy → masked_content
      │
      ▼
masked_content == original_content ?
      │                    │
     yes                   no
      │                    │
      ▼                    ▼
 SINGLE                store_original for the
representation          matched class?
(everyone gets it)      │              │
                       true           false
                         │              │
                         ▼              ▼
                     DUAL           MASKED-ONLY
                representation    (no original ever
                (original gated    stored, for anyone)
                 by class_grants)
```

Three outcomes, and they matter because they lead to genuinely different guarantees:

- **Single.** Masking made no change — the chunk was tagged `FINANCIAL` because it sits in a compensation section, say, but this particular chunk is just "employees are eligible for the annual bonus program," no literal value present. There's nothing to protect, so there's one representation, and everyone with ordinary resource access sees it. The classification tag is kept for provenance and audit, but it does not gate this chunk.
- **Dual.** Masking changed the content, and the matched class's policy says an authorized-only original may exist (`store_original: true` — the default for `PERSONAL` and `FINANCIAL`). Two forms are computed and stored: a `masked` one, always available, and an `original` one, gated by `class_grants`.
- **Masked-only.** Masking changed the content, and the matched class's policy says no original may ever be persisted (`store_original: false` — the default for `RESTRICTED`, e.g. an SSN). Only the masked form is computed for storage, full stop. There is no "authorized" tier here, because there is no original artifact to authorize access to — not even a `RESTRICTED`-cleared caller gets it.

That third case deserves emphasis, because it's easy to assume "restricted" content just needs stricter authorization. It doesn't — some content should never exist in a retrievable form *at all*, for anyone, and the platform expresses that as one configuration value on the classification policy rather than as special-cased code:

```yaml
synflux:
  classification:
    policy:
      RESTRICTED:
        action: MASK
        store_original: false     # masked-only, for everyone — SSNs live here
      PERSONAL:
        action: MASK
        store_original: true      # dual representation — authorized callers get the original
      FINANCIAL:
        action: MASK
        store_original: true
```

The masking stage runs after chunking and strictly **before** the Cassandra commit described in Chapter 9 — the same "cache before bus" ordering that makes ingestion crash-safe also makes masking irrevocable in the right direction. Once a Masked-only chunk is committed, the original text was never written anywhere to begin with; there is nothing left to leak.

*Source: `docs/architecture/synanton-design-1.23.md` §3.2, §3.2a.*

## 14. Two Representations, One Chunk

A Dual-outcome chunk doesn't just exist twice conceptually — it is stored twice, consistently, everywhere the chunk goes:

| Store | Single outcome | Dual outcome | Masked-only outcome |
|---|---|---|---|
| Chunk cache (Cassandra) | one field | `content_masked` + `content_original` | `content_masked` only |
| Embedding cache | one embedding | `embedding_masked` + `embedding_original` | `embedding_masked` only |
| Reverse index (Lucene) | one field | two fields, `content_masked` + `content_original` | one field |
| Knowledge graph | untagged | entities/edges tagged `representation` | masked-derived only |
| Synthesis cache | one entry | representation-aware, filtered by caller | masked entry only |

The embedding row deserves a specific callout: masked text and original text are *different strings*, so they must not be assumed to embed to the same vector — a Dual chunk gets two embeddings, not one embedding reused with an access flag bolted on. The embedding cache key grows from `(tenant, chunk_text_hash)` to `(tenant, class, representation, chunk_text_hash)` precisely because a vector computed over unmasked sensitive text is itself sensitive (embedding inversion is a real attack) and must never be reused across tenants or shared with a caller who only has the masked-eligible representation.

The graph gets the same treatment: for a Dual chunk, entity extraction runs once over the masked text and once over the original text, and a fact that only exists in the original (a specific salary figure, say) produces an edge tagged `representation=original` that simply doesn't exist in the masked extraction. A graph traversal for an unauthorized caller only ever walks `representation=masked` edges.

*Source: `docs/architecture/synanton-design-1.23.md` §3.5.*

## 15. Authorization at Query Time

This is where the earlier design draft and the current one genuinely disagree, and it's worth being explicit about why the current one is right.

The earlier approach filtered by **excluding the whole chunk**: if a caller lacked the `FINANCIAL` class, the compiled query added `class NOT IN ('FINANCIAL')` and the chunk simply never showed up — zero hits, for any query that touched it, authorized or not for the parts of it that weren't actually sensitive.

The current model instead performs **representation selection**, compiled into the query the same way resource ACL clauses always have been — before BM25 statistics are computed or HNSW candidates are gathered, never as a post-filter:

```java
// Before: Must(org_id=acme, space_id=finance)
// After:  Must(org_id=acme, space_id=finance, class IN ('FINANCIAL', 'PUBLIC'))
//         → representation = ORIGINAL for chunks classified FINANCIAL, MASKED otherwise
```

For a Dual‑representation chunk classified `FINANCIAL`:

- A caller holding a `FINANCIAL` class grant resolves to `content_original` / `embedding_original` / `representation=original` graph edges.
- A caller without that grant resolves to `content_masked` / `embedding_masked` / `representation=masked` edges — the chunk is **not excluded**, the masked field is fully searchable, it simply never contains the sensitive literal.

Concretely: a payroll user and an HR user both searching "gross income" both get a hit on the Compensation section of an employee record. The payroll user's hit shows the real figures. The HR user's hit shows `"Gross income: [REDACTED:FINANCIAL]"` — a real, useful, non-zero result that still correctly withholds the number. Masked‑only chunks resolve to `content_masked` for *every* caller, because no original exists to select. Single‑representation chunks resolve to their one field for everyone.

This reaches every layer that could otherwise leak term statistics: BM25 term frequencies are computed against whichever field was selected, the HNSW pre-filter never considers the `embedding_original` vector for an unauthorized caller, and the fast ACL pre-filter (a Cuckoo filter, chosen specifically because it supports O(1) deletion on revocation with no rebuild) gets the same class → representation dimension. And this enforcement is **mandatory for every tenant tier**, not just the `HIGH_SECURITY` ones that used to get the fast path — a `STANDARD` tenant used to fall back to a post-filter that leaked hit counts and term statistics even while correctly hiding the actual sensitive value; that fallback is gone.

*Source: `docs/architecture/synanton-design-1.23.md` §3.3.*

## 16. The Restricted-for-Everyone Case

It's worth returning to the Masked-only case once more, because it's the one that most needs to survive contact with an audit. An SSN, once detected, is never computed for storage in *any* form other than `[REDACTED:SSN]` — not in the chunk cache, not in the embedding cache, not in the Kafka payload, not in the reverse index, not in the graph, not in the synthesis cache. This is true for every caller, including one holding `RESTRICTED` class grants, because `store_original: false` doesn't gate access to an original — it prevents the original from ever coming into existence as a stored artifact.

The one place the literal *does* still exist is the raw source document itself, in `synvault`'s object store — and that's an intentional, separately-protected boundary, not an oversight: raw-object storage sits behind its own encryption and `content:read` grants, entirely outside the search plane's guarantees. A deployment that also needs the raw document sanitized at rest can configure a pre-ingest sanitization pipeline; that's a deployment-time choice, out of scope for the search plane itself.

A fail-closed default backs all of this up: a chunk with a *missing* `classification[]` field — one the detector pipeline hasn't run against yet, say, mid-migration — is treated as `RESTRICTED`, i.e. masked-only, until proven otherwise. The system errs toward "nobody sees this" rather than "everyone sees this," which is the only direction it's safe to be wrong in.

*Source: `docs/architecture/synanton-design-1.23.md` §3.2a, §5.*

## 17. Defense Beyond the Index

Representation selection at the index closes the main leak surface, but a handful of query-side channels can still betray sensitive content if they're not deliberately included in the same model:

| Channel | Risk without a specific control |
|---|---|
| Suggest / autocomplete | Could complete a term that only occurs in a caller's inaccessible `content_original` field |
| The anomaly-detection Kafka topic | Streams raw query text for offline analysis — including queries that themselves contain a restricted pattern |
| Execution trace | Per-class hit counts and statistics, returned to the caller alongside results |
| Highlight snippets | Rendered directly from chunk content — easy to accidentally render from the wrong representation |

Each of these reuses the same `ClassificationDetector` patterns at query time rather than inventing a second detection mechanism, and each is bound to render from *the representation actually selected for the caller* — a highlight snippet must never fall back to `content_original` just because it happens to be sitting in the same row.

*Source: `docs/architecture/synanton-design-1.23.md` §3.6.*

## 18. Change Without Rewriting

The payoff for keeping classification, masking, and authorization as separate mechanisms is that most real-world changes touch only one of them — and two of the three don't require touching stored data at all.

```
                    CHANGE TYPES

       ┌─────────────────┬──────────────────┐
       │                 │                  │
       ▼                 ▼                  ▼
Authorization       Classification       Masking-policy
mapping change      policy change         change
       │                 │                  │
       ▼                 ▼                  ▼
Query-time          Re-run detector      Re-run masking,
filter changes       + reclassify         re-protect
       │                 │                  │
       ▼                 ▼                  ▼
No index/graph      Requires             Requires
rewrite              reprocessing         reprocessing
```

- **A security-group mapping changes** — say `AUDIT` is newly granted `FINANCIAL` — and nothing about any stored chunk changes. The query-time filter simply admits a different set of classes for that group's callers. This is why `class_grants` propagates over the existing topology outbox the same way resource-ACL grants always have, with the same O(1) revocation path.
- **A classification policy changes** — a new detector, a new table-header pattern — and existing content needs to be re-labeled. A `ReindexAfterPolicyChangeWorkflow` (a Temporal workflow) reads the affected manifests, re-runs the detector with the new policy, and re-indexes — without a full re-ingest from the source.
- **A masking policy changes** — `store_original` flips from `true` to `false` for some class, say — and every affected chunk's stored representations genuinely need to change, because the representation itself is different now. This is the one case that actually requires reprocessing content, and it's worth knowing which of the three you're in before assuming a config change is "just a flag flip."

Underneath all three, the reverse index, vector store, and graph store a chunk's stable classification — never a precomputed copy of every caller's authorization. Authorization is resolved fresh, at query time, against whoever is actually asking.

*Source: `docs/architecture/synanton-design-1.23.md` §3.7; reconciliation of the original v1.23 masking model with this book's original draft, 2026-08-29.*

## 19. Looking Ahead: Privacy-Preserving Cross-Tenant Sharing

Everything in this Part so far governs one axis: within a single tenant, who sees the original versus the masked form of a chunk. There's a separate, **proposed** (not yet adopted) design that addresses a different axis entirely: whether a *sanitized* form of a chunk can be shared *across tenants* at all, for deduplication and cost efficiency — the same HR policy boilerplate, say, showing up nearly verbatim across a hundred customers shouldn't need a hundred separate embeddings.

That proposal introduces its own `PRIVATE` / `PUBLIC` split, and it's easy to conflate with this chapter's `masked` / `original` split because the vocabulary rhymes. They are not the same mechanism:

| Axis | What it decides | Scope |
|---|---|---|
| Masked / original (this Part) | Whether *this caller, in this org*, sees the literal value or a redacted stand-in | Within one tenant, gated by `class_grants` |
| Public / Private (proposed) | Whether a sanitized semantic unit may be deduplicated and served *across tenants at all* | Across tenants, gated by privacy policy |

A chunk can vary along both axes independently: a `FINANCIAL` chunk can have a Dual masked/original split for within-tenant authorization, while separately never qualifying for the cross-tenant `PUBLIC` plane because even its sanitized form isn't safe to deduplicate across organizations. This proposal is not yet part of the approved baseline; it's mentioned here so that if you encounter the term "two-representation model" elsewhere, you know which of the two representations it means.

*Source: `docs/architecture/proposals/v1.23/synanton-design-1.23-two-representation-semantic-chunking.md`.*

---

# Part IV — From Chunk to Knowledge: The Three Stores

## 20. One Chunk, Three Representations

The semantic chunk, with its classification and masking decision resolved, is the common source from which three specialized stores are built:

```
                         SEMANTIC CHUNK
                              │
                 ┌────────────┼────────────┐
                 │            │            │
                 ▼            ▼            ▼
           REVERSE INDEX   VECTOR STORE   GRAPH DB
                 │            │            │
          terms → chunk    vector → chunk  entities
                 │            │            │
                 │            │            └── relationships
                 │            │
                 └────────────┼────────────┘
                              │
                         CHUNK ID
                              │
                       COMMON IDENTITY
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
          provenance     classification    source
```

This isn't accidental duplication — it's three different answers to three different questions, all traceable back to the same evidence.

## 21. The Reverse Index

`synquest`'s lexical layer (Rust, Tantivy, BM25) answers *"where does this term occur?"* — the classic inverted-index question: a term maps to the chunks it appears in, not the other way around. Indexing at chunk granularity rather than document granularity is what makes a 300-page PDF with one relevant paragraph return that paragraph, with its `section_path`, rather than the whole document. It also gives the security model a natural unit: a search result is a chunk, a chunk carries a classification, and (per Chapter 15) the query that produced the result already selected the right representation before the term statistics were even computed.

Multilingual content gets its own tokenization path — CJK text is handled with an overlapping-bigram tokenizer rather than requiring per-language dictionaries — and every third-party parser call is wrapped in a panic guard, because in a multi-tenant deployment, one malformed document crashing the process denies service to every other tenant on that shard.

*Source: `docs/architecture/synanton-design-1.22.md` §20.*

## 22. The Vector Store

Lexical search answers *"does this contain these words,"* which fails the moment a user's phrasing doesn't match the document's. Someone asking "what are the rules for ending an agreement" should still find a clause that says "either party may terminate this contract" — different words, related meaning. That's what the vector store (`synquest`'s HNSW index, SIMD-optimized) is for.

Each chunk is embedded once (Chapter 8) and the resulting vector is stored keyed back to the chunk it came from — the vector is a *derived representation* of the chunk, never an independent piece of knowledge in its own right. The same classification-and-representation logic from Part III applies here exactly as it does to the reverse index: an HNSW pre-filter excludes the `embedding_original` vector from consideration for a caller who isn't authorized for it, before candidates are even scored, not after.

*Source: `docs/architecture/synanton-design-1.22.md` §7 steps 5, 9; §20.*

## 23. The Knowledge Graph

Search answers *"what matches my question."* The graph, owned by `relix`, answers *"what is connected to what"* — a different kind of question that neither lexical nor vector search can answer well. Given a sentence like "Acme supplies Synanton with network equipment under the 2026 framework agreement," entity extraction identifies `Acme`, `Synanton`, and `Framework Agreement` as nodes and `supplies` / `governed by` as edges — and every edge is computed against **four weighted signals** (an explicit link stated in the source, shared source references between two entities, co-occurrence in the same chunk, and shared ontology type), so "connected" means something more precise than "mentioned near each other once."

Every entity and edge remains traceable back to the chunk it was derived from, and — this is the point that matters for security — **inherits that chunk's classification**. A relationship extracted from a `FINANCIAL` chunk is itself tagged `FINANCIAL` in the graph; graph traversal is filtered by classification (and, per Chapter 14, by representation) exactly the way search is. Without this, the graph would become an unintended bypass around every other layer of document and search security — a caller denied a fact in search could otherwise just ask the graph for the same fact by a different route.

`relix` also runs the pluggable-connector pattern (Neo4j, Amazon Neptune, or an in-memory connector for tests) behind a single SPI, materialized graph views with incremental refresh for hot traversal patterns, and a nightly community-detection job that surfaces sparsely-connected clusters as knowledge-gap signals in the admin console — none of which changes the security story above; they're all built on top of the same classified, provenance-tracked graph.

*Source: `docs/architecture/synanton-design-1.22.md` §21.*

## 24. Hybrid Retrieval

The three stores aren't exclusive alternatives — a single question is often naturally multi-stage. "Which suppliers have contracts expiring soon, and what are the termination requirements?" decomposes into a graph lookup (find suppliers), a lexical search (find contract clauses), and a vector search (find semantically related termination language), fused into one evidence set.

Mechanically, lexical and semantic hits are combined via Reciprocal Rank Fusion — a rank-based combination rather than a raw-score blend, which is what makes cross-phase score comparisons meaningful at all. An optional graph-expansion phase, seeded from the top lexical/semantic candidates, can add relational context before an optional reranking pass and a final budget-trim bring the candidate set down to what actually fits in the response (or, for a synthesis query, the LLM context window).

*Source: `docs/architecture/synanton-design-1.22.md` §7.*

## 25. Security Across All Three Stores

The one property that must never vary between the three stores is what "safe" means. If the reverse index, vector store, and graph disagreed about a chunk's classification, or about which representation an unauthorized caller should see, the platform would effectively have three independent security models bolted together — and a caller denied by one could simply ask another.

```
                 SEMANTIC CHUNK
                       │
                classification
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
       Reverse       Vector       Graph
        Index        Store         DB
          │            │            │
          └────────────┼────────────┘
                       │
                same classification,
                same representation logic
                       │
                       ▼
                QUERY-TIME POLICY
                       │
                       ▼
                 secure retrieval
```

> **Denormalization creates multiple representations. It must never create multiple security models.**

*Source: `docs/architecture/synanton-design-1.23.md` §3.5; original security-model synthesis, 2026-08-29.*

---

# Part V — Operating Synanton Securely

## 26. Observability for Security

The security model is only as good as the platform's ability to notice when it's violated. v1.23 adds metrics purpose-built for this: spans detected and the action taken per class, documents quarantined and why, chunks rejected at query time by the representation filter, and query-time denials by role. Two alerts sit on top of them: `RestrictedContentDetectedInIndex` (page severity — this should never fire, and if it does, something upstream of the index failed) and `ClassificationDetectorFailureRate` (warning, if detector errors exceed 1% over five minutes, which is the leading indicator of documents piling up in quarantine).

*Source: `docs/architecture/synanton-design-1.23.md` §3.8.*

## 27. Remediation

Two workflows exist for the two ways protected content needs to change after the fact. When content must be erased outright — a GDPR request — the existing erasure cascade removes it from every data plane (search index, graph, caches, and eventually cold storage) within a p99 of 45 seconds, using compare-and-swap reference counting in the graph so two concurrent deletions that both cite the same entity can't race into either a double-deletion or an orphaned node. When a *classification policy* changes rather than the content itself — a new detector pattern goes live, say — `ReindexAfterPolicyChangeWorkflow` re-labels and re-indexes the affected manifests without a full re-ingest, which is the "requires reprocessing, but not from the source" branch from Chapter 18's change-type diagram.

*Source: `docs/architecture/synanton-design-1.22.md` §10; `docs/architecture/synanton-design-1.23.md` §3.7.*

## 28. Testing Discipline

A `test:security` CI tier runs on every pull request that touches a classification-related path, against a fixture document containing all three classes deliberately. It asserts, among other things: a masked-only literal (an SSN) appears in zero stores for any role; a Dual-representation query from an unauthorized role returns the masked field, not zero hits and not the literal; the same query from an authorized role returns the original; and an unmodified (Single-representation) chunk returns identical content to every role. The last assertion matters as much as the first three — it's the regression test that catches a change accidentally turning "safe to show everyone" content into something wrongly gated.

*Source: `docs/architecture/synanton-design-1.23.md` §3.9; `docs/demos/classification-aware-semantic-search-demo.md` §6.*

---

## Where to Go Next

This book simplifies. The documents below don't, and are the ones to trust when they disagree with this one:

| Question | Document |
|---|---|
| What does the platform do overall, module by module? | `docs/architecture/synanton-design-1.22.md` |
| What exactly does the v1.23 classification/masking/authorization model specify? | `docs/architecture/synanton-design-1.23.md` |
| What's actually built vs. still planned, phase by phase? | `docs/implementation/classification-aware-search/INDEX.md` |
| How do I run the classification-aware search demo myself? | `docs/demos/classification-aware-semantic-search-demo.md` |
| What does the structured extraction contract actually specify? | `docs/architecture/proposals/v1.21/Synanton_v1.21_Structured_content_extraction_plane.md` |
| How does semantic chunking decide boundaries? | `docs/architecture/proposals/v1.22/Synanton v1.22  Structured Content Semantic Chunking Design Proposal.md` |
| What's the proposed cross-tenant privacy-preserving sharing model? | `docs/architecture/proposals/v1.23/synanton-design-1.23-two-representation-semantic-chunking.md` |

### Glossary

| Term | Meaning |
|---|---|
| **Chunk** | A semantically coherent unit of a document — the base unit for indexing, embedding, and classification. |
| **Classification** | A label (`PUBLIC`, `PERSONAL`, `FINANCIAL`, `RESTRICTED`) describing what kind of information a chunk contains. |
| **Masking** | Replacing a sensitive literal span with a redacted stand-in (`[REDACTED:CLASS]`) before it can be stored. |
| **Single / Dual / Masked-only** | The three possible outcomes of the masking decision for a chunk — see Chapter 13. |
| **`store_original`** | The per-classification policy flag deciding whether an authorized-only original representation may ever exist for that class. |
| **Representation selection** | Choosing, at query compile time, whether a caller's query targets a chunk's masked or original field/embedding/graph-tag. |
| **`class_grants`** | The authorization table mapping a subject to the classifications they may search or view — independent of resource ACLs. |
| **Reverse index** | The lexical (BM25) search structure mapping terms to the chunks that contain them. |
| **HNSW** | The approximate-nearest-neighbour index structure behind vector (semantic) search. |
| **GraphRAG** | Retrieval that combines vector search with knowledge-graph traversal for relational context. |
