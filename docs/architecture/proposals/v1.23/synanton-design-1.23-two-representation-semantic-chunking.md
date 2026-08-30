# Synanton Platform --- Architecture v1.23

## Semantic Two-Representation Model for Secure Multi-Tenant Search

> Document type: Architecture design proposal\
> Version: 1.23-PPR-SC\
> Date: 2026-08-28\
> Status: Proposed\
> Extends: Synanton v1.23 classification and isolation model

------------------------------------------------------------------------

## 0. Relationship to the Base v1.23 Original/Masked Representation Model

The base [`synanton-design-1.23.md`](../../synanton-design-1.23.md) §3.2a
also now describes a two-representation idea --- `content_masked` /
`content_original` --- but along a **different axis** than this
proposal's PUBLIC/PRIVATE split, and the two are easy to conflate
because of the shared vocabulary:

  Axis                          What it decides                                                     Scope
  ----------------------------- -------------------------------------------------------------------- -----------------------------
  Base doc §3.2a: masked/original   Whether *this caller, in this org*, sees the literal sensitive value or a redacted stand-in  Within one tenant, gated by `class_grants`
  This proposal: PUBLIC/PRIVATE     Whether a sanitized semantic unit may be deduplicated and served *across tenants at all*     Across tenants, gated by privacy policy

A chunk therefore varies along **both** axes independently: a
`FINANCIAL` chunk can be Dual (masked/original) under §3.2a for
within-tenant authorization, while separately being PRIVATE-only under
this proposal because its sanitized form still isn't safe to
deduplicate across organizations. The `PRIVATE` representation in this
proposal's §7.1 is **not** the same thing as the base doc's
`content_original` --- `PRIVATE` here is scoped to the tenant boundary
and is itself subject to §3.2a's masked/original split before any
within-tenant retrieval decision is made.

------------------------------------------------------------------------

## 1. Executive Summary

Synanton v1.23 introduces chunk-level classification, class grants,
class-aware retrieval filtering, fail-closed ingestion, and protection
against embedding inversion by preventing cross-tenant reuse of
non-PUBLIC representations.

This proposal extends v1.23 with two related ideas:

1.  **Privacy-Preserving Public Representation (PPR)** --- sensitive
    content can produce a sanitized semantic representation before it is
    embedded into the shared/public semantic plane.
2.  **Semantic Chunk Reuse (SCR)** --- semantic units become the primary
    reusable unit for embedding and cache deduplication, instead of
    arbitrary fixed-size chunks.

The resulting model is:

``` text
                         SOURCE DOCUMENT
                                |
                         structured extraction
                                |
                         semantic decomposition
                                |
                         classification
                                |
                  +-------------+-------------+
                  |                           |
              PRIVATE                      PUBLIC
           representation             representation
                  |                           |
            original data            sanitized data
                  |                           |
          private embedding          public embedding
                  |                           |
       tenant/class isolated       cross-tenant eligible
```

The key architectural principle is:

> **Never share a sensitive representation. Share only a deliberately
> sanitized semantic representation whose privacy policy permits
> cross-tenant reuse.**

Semantic chunking makes this model more efficient because reusable
public knowledge can be deduplicated independently from tenant-specific
sensitive content.

------------------------------------------------------------------------

# 2. Problem

A multi-tenant RAG platform has two conflicting requirements.

### Security

Sensitive information must not leak through:

-   raw documents
-   snippets
-   vector embeddings
-   graph data
-   synthesis context
-   caches
-   retrieval side channels

Embedding inversion makes the vector store itself part of the security
boundary.

Therefore:

``` text
classified source
      ↓
original embedding
      ↓
tenant/class scoped
```

is the safe default.

### Efficiency

The same enterprise knowledge is often repeated across tenants:

-   HR policies
-   security policies
-   product documentation
-   compliance procedures
-   templates
-   common terminology
-   corporate process descriptions

If every classified chunk receives a tenant-local embedding,
computational reuse is lost.

The objective is therefore:

> Preserve v1.23's security guarantees while recovering safe semantic
> deduplication for information that can be transformed into a public
> representation.

------------------------------------------------------------------------

# 3. Design Goals

The v1.23 extension MUST:

1.  Preserve the original v1.23 authorization model.
2.  Never expose original sensitive embeddings through the PUBLIC plane.
3.  Generate PUBLIC embeddings only from sanitized representations.
4.  Make semantic units reusable independently of source documents.
5.  Allow cross-tenant cache reuse only for explicitly eligible PUBLIC
    representations.
6.  Prevent PUBLIC queries from using sanitization as an authorization
    bypass.
7.  Version privacy transformations.
8.  Fail closed when public sanitization cannot be safely completed.
9.  Measure privacy leakage and retrieval utility independently.
10. Support incremental migration from the original v1.23 model.

------------------------------------------------------------------------

# 4. Non-Goals

This proposal does not attempt to:

-   prove differential privacy for embeddings;
-   guarantee zero information leakage;
-   replace resource ACLs;
-   replace class grants;
-   make original sensitive embeddings safe to share;
-   use LLM-generated transformations as the default sanitization
    mechanism;
-   solve raw-document authorization.

Sanitization is a **privacy boundary and optimization**, not an
authorization mechanism.

------------------------------------------------------------------------

# 5. Semantic Unit as the Reusable Primitive

The current architecture treats a chunk primarily as a retrieval unit.

The proposed model makes the **semantic unit** the reusable primitive.

Conceptually:

``` text
document
  |
  +-- semantic unit A
  +-- semantic unit B
  +-- semantic unit C
  +-- ...
```

A semantic unit SHOULD correspond to a coherent piece of meaning, such
as:

-   a policy section
-   a table
-   an employee record
-   a procedure step
-   a product description
-   a paragraph group
-   a structured entity/relationship group

The semantic unit SHOULD preserve provenance back to the original
document and source offsets/elements.

This is important because privacy transformation should operate on
meaningful boundaries rather than arbitrary token windows whenever
possible.

------------------------------------------------------------------------

# 6. Why Semantic Chunking Improves Reuse

Consider:

``` text
Tenant A
  compensation policy
  employee records
  promotion policy

Tenant B
  compensation policy
  employee records
  promotion policy
```

With fixed chunks, sensitive and reusable content can be mixed:

``` text
chunk 1:
policy + employee salary + policy

chunk 2:
employee + bonus + promotion
```

This makes safe sharing difficult.

Semantic decomposition can instead produce:

``` text
PUBLIC:
  compensation policy
  promotion policy

PRIVATE:
  employee salary
  employee bonus
```

The public semantic units can then be content-addressed:

``` text
public_semantic_hash
        |
        +---- Tenant A
        +---- Tenant B
        +---- Tenant C
        +---- Tenant N
```

One embedding can serve many tenants when the sanitized semantic
representation is identical.

------------------------------------------------------------------------

# 7. Two-Representation Model

Each semantic unit MAY have two representations.

``` text
                         SEMANTIC UNIT
                               |
                    +----------+----------+
                    |                     |
                PRIVATE                 PUBLIC
             representation          representation
                    |                     |
             original content        sanitized content
                    |                     |
             private embedding       public embedding
                    |                     |
             private index/cache     shared index/cache
```

## 7.1 Private Representation

The private representation contains the original semantic content.

It remains subject to:

-   resource ACL
-   `class_grants`
-   tenant isolation
-   class-aware BM25 filtering
-   class-aware HNSW filtering
-   private graph access
-   private synthesis
-   no cross-tenant sharing for non-PUBLIC data

The private embedding MUST be treated as sensitive data.

## 7.2 Public Representation

The public representation is derived from the semantic unit using a
versioned privacy policy.

Example:

Original:

``` text
Employee: Alice Smith
Role: Senior Engineer
Salary: €127,500
Bonus: €18,000
Department: Engineering
```

Public:

``` text
Employee: Alice Smith
Role: Senior Engineer
Salary: [MONEY]
Bonus: [MONEY]
Department: Engineering
```

The public representation may be indexed and cached across tenants only
when its policy allows this.

------------------------------------------------------------------------

# 8. Privacy-Preserving Transformation

The transformation operates on sensitive spans detected during
classification.

Example:

``` json
{
  "type": "MONEY",
  "class": "FINANCIAL",
  "start": 42,
  "end": 50,
  "action": "MASK",
  "replacement": "[MONEY]"
}
```

Supported actions SHOULD include:

``` text
PRESERVE
MASK
DROP
BUCKET
SUPPRESS_SECTION
```

Examples:

  Original          Public representation
  ----------------- -----------------------
  €127,500          `[MONEY]`
  15%               `[PERCENT]`
  2026-08-15        `[DATE]`
  +31 6 12345678    `[PHONE]`
  IBAN              `[BANK_ACCOUNT]`
  SSN               `[SSN]`
  salary €127,500   salary `[MONEY]`

The transformation SHOULD preserve useful semantic structure where
policy permits.

------------------------------------------------------------------------

# 9. Semantic vs Literal Sensitivity

A critical distinction is required.

Removing a literal does not necessarily remove the information it
conveys.

For example:

``` text
Salary: [MONEY]
Bonus: [MONEY]
Net salary: [MONEY]
```

may still reveal sensitive relationships.

Likewise:

``` text
Salary: [HIGH]
```

leaks more information than:

``` text
Salary: [MONEY]
```

Therefore privacy policies MUST consider:

-   sensitive literals;
-   sensitive attributes;
-   semantic relationships;
-   document structure;
-   neighboring semantic units;
-   graph relationships;
-   aggregate inference.

Highly sensitive classes SHOULD default to stronger transformations such
as `DROP`.

------------------------------------------------------------------------

# 10. Privacy Levels

A deployment MAY select different public representations.

### STRUCTURAL

``` text
Salary: [MONEY]
```

Preserves semantic type but removes value.

### BUCKETED

``` text
Salary: [SALARY_100K_150K]
```

Preserves coarse range.

### SEMANTIC

``` text
Salary: [HIGH_SALARY]
```

Preserves qualitative information.

### DROP

``` text
Salary information removed.
```

The default policy for financial or personal data SHOULD be
conservative.

------------------------------------------------------------------------

# 11. Public Embedding Identity

Public embeddings MUST be content-addressable and policy-versioned.

Recommended identity:

``` text
SHA256(
    normalized_public_semantic_content
    || embedding_model
    || model_version
    || privacy_policy_version
)
```

Therefore:

``` text
same sanitized semantic content
+ same embedding model
+ same model version
+ same privacy policy
=
same public embedding
```

A policy change MUST invalidate affected public embeddings.

------------------------------------------------------------------------

# 12. Semantic Chunk Identity

Separate the semantic unit identity from the source-document identity.

Recommended metadata:

``` json
{
  "semantic_unit_id": "...",
  "source_document_id": "...",
  "source_offsets": "...",
  "semantic_content_hash": "...",
  "classification": ["FINANCIAL"]
}
```

For public reuse:

``` json
{
  "public_content_hash": "...",
  "privacy_policy_version": "1",
  "embedding_model": "...",
  "embedding_version": "..."
}
```

This allows multiple tenants/documents to reference the same public
semantic representation.

------------------------------------------------------------------------

# 13. Public/Private Index Architecture

The logical architecture becomes:

``` text
                         SYNANTON
                            |
                  semantic decomposition
                            |
                 +----------+----------+
                 |                     |
             PUBLIC PLANE          PRIVATE PLANE
                 |                     |
          sanitized units          original units
                 |                     |
          public embeddings       private embeddings
                 |                     |
          shared HNSW/index       ACL/class filtered
                 |                     |
       cross-tenant cache       tenant/class cache
```

The PUBLIC plane MUST NOT contain:

-   original sensitive text;
-   original sensitive embeddings;
-   private graph entities;
-   private synthesis context;
-   unredacted snippets;
-   private cache entries.

------------------------------------------------------------------------

# 14. Query Routing

Sanitized representations do not eliminate authorization.

Queries MUST be classified or policy-evaluated before retrieval.

### Public query

``` text
How is employee compensation structured?
```

May use the PUBLIC plane.

### Sensitive query

``` text
Who earns €127,500?
```

A PUBLIC caller MUST NOT use the public representation to bypass
authorization.

The system MUST:

-   deny the query, or
-   route it to an authorized PRIVATE path.

For authorized users:

``` text
non-sensitive semantic query → PUBLIC plane
sensitive query              → PRIVATE plane
```

------------------------------------------------------------------------

# 15. Cache Architecture

The cache model becomes representation-aware.

### Private

``` text
PRIVATE embedding
    ↓
tenant/class scoped
    ↓
no cross-tenant reuse
```

### Public

``` text
PUBLIC embedding
    ↓
policy validated
    ↓
cross-tenant reuse allowed
```

Synthesis cache reuse follows the same rule.

A synthesis result MAY be shared across tenants only if every source
representation is PUBLIC and all participating privacy policies are
compatible.

A single PRIVATE source forces the result into a private cache domain.

------------------------------------------------------------------------

# 16. Graph Representation

The two-representation model applies to graph data as well.

Entities and relationships derived exclusively from sanitized public
semantic units MAY enter the PUBLIC graph.

Entities or relationships derived from private semantic units remain
private.

A public graph MUST NOT reconstruct sensitive values through:

-   entity attributes;
-   edge metadata;
-   aggregation;
-   node labels;
-   provenance;
-   neighboring relationships.

------------------------------------------------------------------------

# 17. Ingestion Pipeline

Updated ingestion flow:

``` text
Acquire
  |
Parse
  |
Structured extraction
  |
Semantic decomposition
  |
ClassificationDetector
  |
Sensitive span extraction
  |
+-----------------------------+
|                             |
PRIVATE                    PUBLIC
representation           transformation
|                             |
original content          sanitized content
|                             |
private embedding         public embedding
|                             |
private index/cache       public index/cache
```

The public path MUST NOT fall back to the private/original
representation if sanitization fails.

Correct behavior:

``` text
public transformation failure
        ↓
public representation unavailable
        ↓
do not publish publicly
        ↓
alert/metric
```

Private ingestion can continue according to normal v1.23 fail-closed
rules.

------------------------------------------------------------------------

# 18. Security Model

The two-representation model adds a defense-in-depth boundary:

``` text
SOURCE SECURITY
     |
     +-- ACL / class grants
     |
REPRESENTATION SECURITY
     |
     +-- private representation
     +-- public representation
     |
RETRIEVAL SECURITY
     |
     +-- class-aware filtering
     +-- query policy
     |
CACHE SECURITY
     |
     +-- representation-aware reuse
```

The fundamental invariant is:

> Authorization controls access to information. Representation policy
> controls what information is allowed to enter a shared semantic
> domain.

------------------------------------------------------------------------

# 19. Performance Hypothesis

The primary cost of the two-representation model is additional ingestion
work:

-   semantic decomposition;
-   privacy transformation;
-   public embedding;
-   public index update.

The primary benefit is safe reuse.

For highly repetitive enterprise workloads:

``` text
N tenants
×
same public semantic unit
```

can become:

``` text
1 public embedding
+
N tenant references
```

instead of:

``` text
N private embeddings
```

### Planning estimates

  Workload                           Potential public embedding compute reduction
  -------------------------------- ----------------------------------------------
  Low duplication                                                          0--10%
  Typical enterprise                                                      15--30%
  Highly templated tenants                                                30--60%
  Near-identical knowledge bases                                          60--90%

These numbers are hypotheses and require benchmarking.

Overall v1.23 infrastructure overhead could plausibly move from
approximately:

``` text
original v1.23: +10–15%
```

toward:

``` text
PPR + semantic reuse: +5–10%
```

and potentially approach the original v1.22 economics for workloads with
high public semantic duplication.

------------------------------------------------------------------------

# 20. Important Limitation: Semantic Equivalence

Exact content hashing only detects identical public semantic units.

Two units may be semantically equivalent while textually different:

``` text
Tenant A:
Employees receive an annual performance bonus.

Tenant B:
Staff are eligible for a yearly performance bonus.
```

A future optimization could canonicalize these into a shared
representation.

However, the first implementation SHOULD use deterministic normalization
rather than an LLM-generated canonicalizer.

Possible future pipeline:

``` text
sanitized semantic unit
        ↓
deterministic normalization
        ↓
exact content hash
        ↓
public embedding
```

Only later:

``` text
sanitized semantic unit
        ↓
privacy-safe semantic canonicalization
        ↓
canonical representation
        ↓
public embedding
```

Semantic canonicalization MUST itself be treated as a potential privacy
boundary.

------------------------------------------------------------------------

# 21. Security Evaluation

PPR MUST be evaluated empirically.

Required tests:

-   embedding inversion;
-   attribute inference;
-   membership inference;
-   masked-value reconstruction;
-   structure-based inference;
-   cross-chunk inference;
-   graph inference;
-   synthesis leakage.

Compare:

``` text
original embedding
vs.
sanitized embedding
```

and measure both leakage and utility.

The system SHOULD define an explicit leakage threshold before enabling
cross-tenant public reuse for each classification.

------------------------------------------------------------------------

# 22. Retrieval Evaluation

For each privacy policy measure:

``` text
Recall@K
MRR
NDCG
semantic similarity
query latency
HNSW candidates
candidate rejection ratio
```

Compare:

``` text
original private representation
sanitized public representation
```

The target is not identical retrieval.

The target is:

> Maximum useful semantic recall subject to an acceptable privacy
> leakage threshold.

------------------------------------------------------------------------

# 23. Required Metrics

Add:

``` text
synflux_semantic_units_created_total

synflux_public_representation_created_total
synflux_public_representation_rejected_total
synflux_privacy_transform_spans_total

embedding_cache_hit_ratio{representation}
embedding_cache_cross_tenant_reuse_total{representation}

synthesis_cache_hit_ratio{representation}
synthesis_cache_cross_tenant_reuse_total{representation}

public_embedding_compute_seconds
private_embedding_compute_seconds

public_search_qps
private_search_qps

semantic_unit_dedup_ratio
public_embedding_dedup_ratio

privacy_query_denied_total
privacy_query_transformed_total
```

Security:

``` text
public_sensitive_literal_detected_total
public_embedding_inversion_failures
public_attribute_inference_failures
public_synthesis_leakage_failures
```

------------------------------------------------------------------------

# 24. Security CI Gate

Add a `test:privacy` tier.

Assertions:

1.  No original sensitive literals exist in PUBLIC stores.
2.  No original sensitive embeddings exist in PUBLIC indexes.
3.  PUBLIC cache entries are explicitly marked `PUBLIC`.
4.  PUBLIC cache entries contain a valid privacy policy version.
5.  PUBLIC queries cannot retrieve PRIVATE semantic units.
6.  PUBLIC synthesis cannot receive PRIVATE context.
7.  Sanitization failures never publish original content.
8.  Privacy attack benchmarks remain below policy thresholds.

Example:

``` bash
grep -r '"representation":"PRIVATE"' public-cache/ && exit 1
grep -r '"class":"RESTRICTED"' public-index/ && exit 1
```

------------------------------------------------------------------------

# 25. Migration

Migration from the original v1.23 architecture:

1.  Deploy representation-aware metadata.
2.  Keep PPR disabled.
3.  Enable semantic decomposition.
4.  Measure semantic-unit duplication.
5.  Enable PPR for PUBLIC-safe classes.
6.  Run inversion and inference tests.
7.  Measure retrieval-quality degradation.
8.  Enable cross-tenant public embedding reuse.
9.  Enable public synthesis-cache reuse.
10. Roll out by tenant and classification.

If PPR is disabled, behavior remains equivalent to the original v1.23
model.

------------------------------------------------------------------------

# 26. Architecture Decision

**Adopt semantic chunking as the preferred unit for the v1.23
two-representation model.**

The model should not be:

``` text
document → fixed chunks → private/public
```

but:

``` text
document
   ↓
semantic units
   ↓
classification
   ↓
private/public representation
   ↓
representation-specific embedding
```

This makes security classification more granular and creates a natural
deduplication boundary.

------------------------------------------------------------------------

# 27. Final Architecture

``` text
                         DOCUMENT
                            |
                    structured extraction
                            |
                     semantic chunking
                            |
                      classification
                            |
              +-------------+-------------+
              |                           |
          PRIVATE                      PUBLIC
        semantic unit              semantic unit
              |                           |
        original content            sanitized content
              |                           |
       private embedding           public embedding
              |                           |
       tenant/class ACL            shared public index
              |                           |
       private cache               shared cache
              |                           |
       private synthesis          public synthesis
```

The resulting system has three layers of protection:

``` text
1. AUTHORIZATION
   Who can access the information?

2. REPRESENTATION
   What form of the information may enter each security domain?

3. RETRIEVAL/CACHE
   Where can the representation be searched, reused, and synthesized?
```

This provides a stronger balance between enterprise security and
multi-tenant efficiency than either a single unrestricted representation
or a model where every classified chunk is permanently isolated.

The central design principle remains:

> **Never share sensitive representations. Share only privacy-preserving
> semantic representations whose policy explicitly permits reuse.**
