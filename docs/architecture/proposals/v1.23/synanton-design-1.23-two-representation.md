# Synanton Platform - Architecture (v1.23) --- Two-Representation Security Model

> Document type: Definitive engineering proposal\
> Version: 1.23-PPR\
> Date: 2026-08-28\
> Status: Proposed\
> Based on: Synanton Architecture v1.23

## 1. Motivation

Synanton v1.23 introduces chunk-level classification, class grants,
compile-time class filtering, deterministic classification detection,
fail-closed ingestion, and protection against embedding inversion by
preventing non-PUBLIC vectors from being shared across tenants.

That security model is correct, but it has an efficiency cost:
classified content loses cross-tenant embedding and synthesis-cache
reuse.

This proposal adds a **Two-Representation Model** with a
**Privacy-Preserving Public Representation (PPR)**.

The key principle is:

> A sensitive source does not need one representation that is either
> completely private or completely public. Synanton can derive a
> sanitized representation that preserves useful semantic structure
> while removing or transforming sensitive literals before embedding.

For example:

Original:

    Employee: Alice Smith
    Role: Senior Engineer
    Salary: €127,500
    Bonus: €18,000
    Department: Engineering

Public representation:

    Employee: Alice Smith
    Role: Senior Engineer
    Salary: [MONEY]
    Bonus: [MONEY]
    Department: Engineering

The original representation remains protected. Only the sanitized
representation can enter the PUBLIC semantic plane.

## 2. Security Objective

The model MUST preserve the v1.23 guarantee that sensitive original
content and its original embedding are not exposed through the public
retrieval plane.

PPR is an additional optimization and privacy boundary. It MUST NOT
replace resource ACLs, class grants, or private class filtering.

The system therefore has two independent properties:

1.  **Authorization:** who may access the original information.
2.  **Representation policy:** what information is allowed to
    participate in public semantic retrieval.

Sanitization MUST NOT be treated as a proof that sensitive information
is impossible to infer.

## 3. Two-Representation Model

Each classified chunk may produce:

``` text
                         SOURCE CHUNK
                              |
                    ClassificationDetector
                              |
                 +------------+------------+
                 |                         |
          PRIVATE REPRESENTATION      PUBLIC REPRESENTATION
                 |                         |
           original content          sanitized content
                 |                         |
          private embedding          public embedding
                 |                         |
        private search/index        public search/index
                 |                         |
        tenant/class scoped        cross-tenant eligible
```

### 3.1 Private representation

The private representation retains the original content.

It is governed by:

-   resource ACL
-   `class_grants`
-   class-aware BM25/HNSW filtering
-   tenant isolation
-   no cross-tenant embedding-cache reuse for non-PUBLIC content
-   no cross-tenant synthesis-cache reuse for non-PUBLIC sources

The private embedding remains protected because embedding inversion
attacks can expose information from vectors even when the source text is
not directly accessible.

### 3.2 Public representation

The public representation is derived from the original structured
content before embedding.

Sensitive spans are transformed according to a privacy policy.

Examples:

  Original           PUBLIC
  ------------------ ------------------
  `€127,500`         `[MONEY]`
  `15%`              `[PERCENT]`
  `2026-08-15`       `[DATE]`
  `+31 6 12345678`   `[PHONE]`
  `12345678`         `[IDENTIFIER]`
  IBAN               `[BANK_ACCOUNT]`
  SSN                `[SSN]`

The transformation SHOULD preserve semantic structure where useful.

Example:

    Salary: €127,500

becomes:

    Salary: [MONEY]

rather than:

    Salary:

This allows semantic queries about compensation structure to remain
useful without embedding the literal salary amount.

## 4. Privacy Transformation Policy

Add a policy configuration:

``` yaml
synflux:
  privacy:
    public_representation:
      enabled: true
      policy_version: "1"
      default_action: MASK

      classes:
        RESTRICTED:
          action: DROP
        PERSONAL:
          action: MASK
        FINANCIAL:
          action: MASK
        PUBLIC:
          action: PRESERVE

      entities:
        MONEY:
          action: MASK
          replacement: "[MONEY]"
        PERCENT:
          action: MASK
          replacement: "[PERCENT]"
        DATE:
          action: MASK
          replacement: "[DATE]"
        PHONE:
          action: MASK
          replacement: "[PHONE]"
        BANK_ACCOUNT:
          action: DROP
        SSN:
          action: DROP
```

Policies MUST be versioned.

A policy change MUST invalidate affected public representations and
their derived embeddings.

## 5. Sensitive Span Model

The classifier SHOULD produce explicit spans:

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

The detector operates on structured extraction elements so that table
structure, headings, labels, and semantic context are available.

This is preferable to a blind regex pass over flattened text.

## 6. Representation Schema

Extend the chunk model:

``` json
{
  "chunk_id": "...",
  "classification": ["FINANCIAL"],

  "private_representation": {
    "content_ref": "...",
    "embedding_ref": "..."
  },

  "public_representation": {
    "content_hash": "...",
    "policy_version": "1",
    "embedding_ref": "...",
    "available": true
  }
}
```

The original content MUST NOT be copied into the public representation.

The public representation MUST be independently materialized and
validated before publication to a shared/public index.

## 7. Public Embedding Identity

Public embeddings MAY be shared across tenants only when generated from
the same sanitized representation and policy.

Recommended key:

``` text
SHA256(
    normalized_public_content
    || embedding_model
    || model_version
    || privacy_policy_version
)
```

This means:

``` text
same public content
+ same model
+ same model version
+ same privacy policy
= same public embedding
```

This restores safe computational deduplication for public
representations.

## 8. Index Architecture

The semantic plane is logically divided into:

``` text
PUBLIC PLANE
  sanitized chunks
  public embeddings
  shared HNSW
  cross-tenant cache eligible

PRIVATE PLANE
  original chunks
  original embeddings
  ACL/class filtered HNSW
  tenant/class scoped cache
```

The PUBLIC plane MUST NOT contain:

-   original sensitive literals
-   original sensitive embeddings
-   private graph entities
-   private synthesis-cache entries
-   unredacted snippets

The PRIVATE plane retains the full v1.23 enforcement model.

## 9. Query Routing

Queries MUST be classified before execution.

There are two important cases.

### 9.1 Public semantic query

Example:

    How is employee compensation structured?

This can search the PUBLIC representation.

### 9.2 Sensitive-value query

Example:

    Who earns €127,500?

A PUBLIC caller MUST NOT use the sanitized representation to bypass
authorization.

The gateway MUST either:

-   deny the sensitive query for PUBLIC callers, or
-   transform the query into a non-sensitive semantic query where policy
    permits.

For authorized callers, the query may be routed to the PRIVATE plane.

Therefore:

``` text
PUBLIC caller
    |
    +-- non-sensitive query --> PUBLIC plane
    |
    +-- sensitive query -----> deny / authorized private path

AUTHORIZED caller
    |
    +-- public semantics ----> PUBLIC plane
    |
    +-- sensitive semantics -> PRIVATE plane
```

## 10. Cache Model

The previous v1.23 rule remains for original classified representations:

``` text
PRIVATE embedding
    -> no cross-tenant sharing
```

New rule:

``` text
PUBLIC sanitized embedding
    -> cross-tenant sharing allowed
```

Synthesis cache follows the same principle.

A synthesis result is cross-tenant reusable only if every source
representation participating in the result is PUBLIC and was generated
under compatible privacy policy versions.

Cache metadata SHOULD include:

``` json
{
  "representation": "PUBLIC",
  "policy_version": "1",
  "class_set": ["PUBLIC"],
  "source_scope": "CROSS_TENANT"
}
```

Any PRIVATE source forces the result into the private cache domain.

## 11. Information Leakage Considerations

PPR reduces exposure but does not provide an absolute mathematical
guarantee of privacy.

The following MUST be tested:

-   embedding inversion
-   membership inference
-   attribute inference
-   reconstruction of masked values
-   inference from categorical replacements
-   inference from document structure
-   inference from neighboring chunks
-   inference through graph relationships
-   inference through synthesis responses

For example:

``` text
Salary: [MONEY]
Bonus: [MONEY]
Net salary: [MONEY]
```

may still reveal sensitive relationships even though numeric literals
are removed.

Therefore policies SHOULD support:

-   `MASK`
-   `DROP`
-   coarse bucketing
-   semantic suppression
-   section-level removal
-   graph suppression

## 12. Privacy Levels

A deployment MAY define progressively stronger public transformations.

### STRUCTURAL

    Salary: [MONEY]

### BUCKETED

    Salary: [SALARY_100K_150K]

### SEMANTIC

    Salary: [HIGH_SALARY]

### DROP

    Salary information removed entirely

The default for FINANCIAL should be `STRUCTURAL` only where security
testing demonstrates acceptable leakage.

Highly sensitive classes SHOULD default to `DROP`.

## 13. Graph and Synthesis

PPR applies beyond vector embeddings.

### Graph

Entities derived exclusively from sanitized content MAY enter the public
graph.

Entities containing original sensitive values MUST remain private.

### Synthesis

LLM context assembly MUST use the representation corresponding to the
caller's authorization.

PUBLIC synthesis MUST never receive private chunks as hidden context.

A cache hit MUST NOT bypass representation or class filtering.

## 14. Ingestion Pipeline

Updated flow:

``` text
Acquire
  |
Parse
  |
Structured extraction
  |
Chunk
  |
ClassificationDetector
  |
SensitiveSpan extraction
  |
+-------------------------------+
|                               |
Private representation       Public transformation
|                               |
Original content             Sanitized content
|                               |
Private embedding            Public embedding
|                               |
Private index/cache          Public index/cache
```

Detector failure remains fail-closed for the private path.

Public representation generation failure MUST NOT silently fall back to
the original content.

The correct behavior is:

``` text
public transformation failure
    -> public representation unavailable
    -> no public publication
    -> alert
```

## 15. Performance Impact Estimate

The two-representation model adds work during ingestion:

-   sensitive-span transformation
-   second embedding where public representation exists
-   second index publication
-   additional cache metadata

Expected planning impact:

  -----------------------------------------------------------------------
  Area                                                   Estimated impact
  ------------------------------ ----------------------------------------
  Classification/sanitization                                     +5--15%
  CPU                            

  Ingestion latency                                               +5--15%

  Storage                                                         +5--12%

  Public embedding compute            +0--100% of classified publicizable
                                                                   chunks

  Private embedding compute                                     unchanged

  Cross-tenant embedding cache                       potentially restored
  reuse                          

  Cross-tenant synthesis cache                       potentially restored
  reuse                          

  Overall infrastructure                approximately +5--10% after cache
  overhead                                                       benefits

  Highly repetitive multi-tenant              potentially near v1.22 cost
  workloads                      
  -----------------------------------------------------------------------

These are planning estimates and MUST be validated with benchmark data.

The main expected benefit is not cheaper ingestion. It is increased
`cache_amplification` for safe public representations.

## 16. Required Metrics

Add:

``` text
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

privacy_query_denied_total
privacy_query_transformed_total
```

Security metrics:

``` text
public_embedding_inversion_test_failures
public_sensitive_attribute_leakage_total
public_sensitive_literal_detected_total
```

## 17. Security Test Gate

Extend `test:security` with a `test:privacy` tier.

Assertions:

1.  Original sensitive literals appear in zero PUBLIC stores.
2.  Original sensitive embeddings are never placed in the PUBLIC index.
3.  Public cache entries contain `representation=PUBLIC`.
4.  Public cache entries contain a valid `policy_version`.
5.  PUBLIC queries cannot retrieve private chunks.
6.  PUBLIC queries cannot recover sensitive values through synthesis.
7.  Sanitization failures never publish original content.
8.  Inversion/attribute-inference benchmark remains below the defined
    leakage threshold.

Example:

``` bash
grep -r "127500" public-index/ public-cache/ public-graph/ && exit 1
grep -r '"representation":"PRIVATE"' public-cache/ && exit 1
```

## 18. Backward Compatibility

The existing v1.23 security model remains valid.

Migration:

1.  Deploy representation-aware schema.
2.  Keep PPR disabled.
3.  Enable classification on a canary tenant.
4.  Generate public representations for selected classes.
5.  Run privacy attack and retrieval-quality benchmarks.
6.  Enable cross-tenant public embedding reuse.
7.  Enable public synthesis-cache reuse.
8.  Roll out by tenant/class.

If PPR is disabled, the system behaves according to the original v1.23
private representation model.

## 19. Decision

Adopt the Two-Representation Model as a v1.23 design extension.

The architectural rule is:

> **Never share a sensitive representation. Share only a deliberately
> sanitized representation whose privacy policy permits cross-tenant
> reuse.**

This preserves v1.23's defense against embedding inversion while
recovering a portion of v1.22's multi-tenant cache efficiency.

## 20. Open Questions

1.  What inversion/attribute-inference leakage threshold defines an
    acceptable PUBLIC representation?
2.  Which classes permit `STRUCTURAL`, `BUCKETED`, or `DROP`
    transformations?
3.  Should person names remain in public representations, or should
    identity also be transformed?
4.  Should PUBLIC synthesis be allowed to answer aggregate questions
    about sanitized financial data?
5.  Should public representations be generated for every classified
    chunk or only for classes explicitly configured for PPR?
6.  Should public embeddings use a dedicated embedding model optimized
    for privacy-preserving representations?
7.  Can public graph entities safely share the same representation
    policy as public vectors?

## 21. Final Recommendation

Keep the original v1.23 security boundary.

Add PPR as a second, explicitly derived representation.

The resulting architecture is:

``` text
                  ORIGINAL DOCUMENT
                         |
                  classification
                         |
              +----------+----------+
              |                     |
          PRIVATE                PUBLIC
        representation         representation
              |                     |
        original text          sanitized text
              |                     |
       original embedding     sanitized embedding
              |                     |
       tenant/class ACL       shared public plane
              |                     |
       no cross-tenant        cross-tenant reuse
          sharing              where policy allows
```

This provides a better balance between security, retrieval utility, and
multi-tenant economics than either:

-   storing only original sensitive embeddings, or
-   removing all cross-tenant sharing for every classified chunk.
