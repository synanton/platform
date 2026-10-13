# Proposal: Content Cache Service and Commitix Large-Payload Integration

**Status:** Approved for Phase 0 implementation (v3)\
**Scope:** Synanton Platform Design 1.26 / Architecture 1.0; Commitix
White Paper 1.0; Commitix Phase 1 implementation plan\
**Purpose:** Reconcile the draft Content Service / `ContentStore`
proposal with the current architecture and Commitix contracts before
changing normative documents or implementation.

## TL;DR — Decisions required before implementation

The following decisions are normative for this proposal. Each is
resolved below with a recommended default; reviewers should confirm or
override.

  ------------------------------------------------------------------------
  #    Decision                                  Recommended default
  --- ------------------------------------------ --------------------------
  C1  Payload representation invariant           Three-state:
                                                 `payload_kind` enum
                                                 (`INLINE`, `EXTERNAL`,
                                                 `NONE`) with CHECK
                                                 constraint — forbids both
                                                 simultaneously, allows
                                                 neither

  C2  Idempotency key for Content Cache put      `(tenant_id, digest)` —
                                                 per-tenant dedup, no
                                                 cross-tenant leak

  C3  Retention precedence                       `legal_hold` >
                                                 `tenant_retention` >
                                                 `owner_retention`;
                                                 owner may extend, never
                                                 shorten; erasure/legal-hold
                                                 conflict resolved outside
                                                 Content Cache

  C4  Content reference format                   `v1:` version prefix;
                                                 URI-encoded fields;
                                                 max 512 chars; wire
                                                 format not public API

  C5  Cross-tenant existence leak prevention     Indistinguishable error;
                                                 constant-time comparison;
                                                 metadata queries
                                                 tenant-scoped

  C6  Naming                                     "Content Cache 1.26" for
                                                 the contract; "Content
                                                 Service" for a
                                                 conformant implementation

  I1  Commitix-to-Content Cache authentication   mTLS + JWT with
                                                 `tenant_id` claim;
                                                 path-level tenant
                                                 enforcement

  I2  Timeout for slow (not down) Content Cache  2s connect, 5s read;
                                                 `declare()` fails fast;
                                                 gRPC deadline propagation

  I3  Orphan reconciliation defaults            24h grace; hourly sweep;
                                                 safe-delete rule;
                                                 reference inventory

  I4  Feature flag rollout                       Per-tenant →
                                                 per-intent-type →
                                                 threshold; kill switch
                                                 to inline

  I5  Adapter observability                       See §5.5 metrics list

  I6  `markForDeletion` semantics                 Reference release, not
                                                 delete request;
                                                 ref-count gated
  ------------------------------------------------------------------------

## Executive decision

Adopt **Content Cache 1.26 as the platform-owned contract** and treat a
standalone Content Service as one possible implementation of that
contract—not as a new architectural authority. Add an optional Java
`ContentStore` adapter to Commitix for large intent payloads, while
retaining the current inline payload path as the default and
compatibility baseline.

Do not merge the original draft unchanged. It contains useful design
ideas, but several details conflict with the current contracts or are
internally inconsistent:

1.  Design 1.26 already defines a storage-independent Content Cache
    contract, capability discovery, atomic publication, tenant
    isolation, provenance, large-content handling, retention, and
    conformance levels. The proposal should implement and reference that
    contract, not replace it with a separate API/domain model.
2.  Commitix's current Phase 1 schema requires
    `payload_value BYTEA NOT NULL`; its implementation plan serializes
    payloads into `payload_value`. Remote content references therefore
    require a deliberate, versioned extension and migration, not an
    undocumented schema substitution.
3.  Commitix's transactional guarantee is that the intent row
    participates in the application's transaction and becomes durable
    only on commit. A remote Content Cache write cannot join that
    database transaction. The integration must document the resulting
    orphan window and recovery behavior explicitly.
4.  A digest alone must not become the public authorization boundary.
    Every access must be tenant-scoped and authorized; global physical
    deduplication, if implemented, must not expose cross-tenant
    existence or metadata.
5.  The Content Cache contract does not mandate Go, PostgreSQL, S3, or
    Parquet. Those are implementation choices. Commitix's port remains
    Java because Commitix is a JVM library.

## 1. Source-of-truth boundaries

  -----------------------------------------------------------------------
  Concern                 Authority               Proposal consequence
  ----------------------- ----------------------- -----------------------
  Source identity, source Ingestion 1.28          Content Cache must not
  version, source                                 become the authority
  lifecycle, and source                           for business/source
  provenance                                      metadata.

  Addressable content     Content Cache 1.26      Define the
  artifacts, payload                              service/adapter against
  integrity, storage                              Design 1.26 and its
  abstraction, retention                          conformance
  and cache lifecycle                             requirements.

  Events, commands,       Eventing/Workflow 1.27  Events carry references
  delivery, workflows,                            and identifiers, not
  retries and recovery                            large payloads;
  across planes                                   Commitix does not
                                                   replace 1.27.

  Canonical knowledge and Knowledge 1.25          Artifacts used to
  derived processing                              derive knowledge retain
  lineage                                         source and Processing
                                                   Run provenance.

  Public API and          Platform API 1.32       Do not expose storage
  long-running public                             backend details or make
  Operations                                      an internal content
                                                   reference a substitute
                                                   for the public API
                                                   contract.

  Durable business        Commitix                Commitix owns its
  execution intent and                            intent state and
  attempt lifecycle                               lifecycle; it does not
                                                   guarantee external
                                                   side-effect success.

  Storage, transaction    Commitix ports/adapters Add content
  and execution adapters                          externalization behind
                                                   an adapter; keep
                                                   `commitix-core`
                                                   independent of Content
                                                   Cache clients and
                                                   storage libraries.
  -----------------------------------------------------------------------

### Required architectural rule

Consumers depend on the Content Cache contract, not on a concrete
Content Service, object store, Parquet layout, or database schema. The
Content Cache implementation may be a standalone service, an embedded
adapter, or a PoC implementation, provided it declares and passes the
appropriate Design 1.26 conformance level.

The platform's "large payloads are referenced" invariant applies to
events and cross-plane messages. It does not mean every Commitix payload
must immediately be externalized: Commitix may retain small serialized
payloads inline and use an opaque Content Cache reference for large
payloads.

## 2. Proposed platform-document updates

### 2.1 Update the Content Cache 1.26 implementation proposal

Add a proposal subsection titled **"Reference Content Service
adapter"**. It should state:

-   Content Service is a candidate implementation of Content Cache 1.26,
    not a new platform plane or an independent contract.
-   The initial reference adapter may use a metadata database plus
    S3-compatible object storage. Parquet is optional and must be
    justified by measured batch/workload needs; it is not required by
    Design 1.26.
-   Storage thresholds (for example, 4 KiB inline and object-store
    thresholds) are implementation configuration, not normative API
    semantics. Benchmark and publish supported capabilities.
-   A local filesystem adapter is acceptable for development/CI/PoC but
    must not claim distributed durability.
-   Consumers use stable logical artifact identifiers and contract APIs.
    They must not depend on `storage_uri`, S3 bucket layout, Parquet
    offsets, or database tables.
-   The adapter advertises its conformance level and optional
    capabilities. Do not claim Full Design 1.26 conformance until the
    full suite passes.

### 2.2 Align the artifact model with Design 1.26

The original proposal's `ContentRef(digest PK)` and mutable
`ContentMetadata` are a useful starting point, but are not sufficient as
the entire platform artifact contract. Design 1.26 requires explicit
tenant scope, provenance, representation identity, atomic publication,
integrity, classification/security behavior, deterministic retrieval,
and capability discovery.

Use the following conceptual separation:

-   **Artifact identity:** stable logical identity of a published cache
    artifact/representation.
-   **Payload digest:** SHA-256 of the exact stored bytes, used for
    integrity verification and optional internal deduplication.
-   **Tenant scope and authorization:** required for every read, write,
    metadata query, lifecycle operation, and audit record.
-   **Processing/source provenance:** references the source
    identity/version and Processing Run that produced the artifact;
    Ingestion remains authoritative for source-version history.
-   **Representation:** flattened content, semantic content, chunks,
    embeddings, and other representations remain independently
    identifiable.
-   **Generation:** immutable identity for a particular produced
    artifact set. Reprocessing creates a new generation; it does not
    mutate a published generation in place.
-   **Lifecycle metadata:** retention, legal hold, logical expiration,
    physical deletion, tier transitions, and audit are modeled
    separately.

A digest is not, by itself, an authorized public reference. If the
implementation deduplicates physical bytes across tenants, it must still
preserve tenant-scoped logical references and prevent cross-tenant
existence, timing, metadata, or access side channels (see §2.4.1).

### 2.3 API and publication requirements

Do not replace the Design 1.26 API with a separate minimal
`Put/Get/Head` contract. The Content Service adapter must implement the
applicable Design 1.26 operations and semantics, including:

-   `put` / publication with complete-payload visibility only after
    payload and required metadata are verified;
-   retrieval, including chunk/range or streaming behavior where
    supported (metadata-only `head()` is a future capability, see §5.4);
-   deterministic identity and content-hash verification;
-   tenant isolation, authorization, classification and representation
    handling;
-   provenance and Processing Run linkage;
-   capability discovery and explicit error semantics;
-   retention/expiration and logical invisibility before asynchronous
    physical deletion;
-   observability, correlation/trace propagation, and audit for
    privileged access.

The precise REST/gRPC shape should be specified in the Design 1.26
contract or its API companion, not invented solely for Commitix
integration.

### 2.4 Retention and deletion

Keep retention expiration separate from storage-tier transitions. An
object-store lifecycle policy may move data between tiers, but it must
not independently delete data whose logical retention or legal-hold
rules are owned by Content Cache.

#### 2.4.1 Retention precedence lattice (C3)

Retention policies form a strict lattice. When multiple policies apply
to the same artifact, the **most restrictive** (longest retention)
wins:

```text
legal_hold > tenant_retention > owner_retention
```

-   **Legal hold** is per-artifact, per-tenant, or per-owner (see
    §2.4.2). It always overrides tenant and owner retention.
-   **Tenant retention** is the minimum retention period configured by
    the tenant. It overrides owner retention but cannot override
    legal hold.
-   **Owner retention** is the retention period declared by the producing
    intent/workflow. It may only **extend** beyond the tenant minimum,
    never shorten it.

**Erasure vs. legal hold (N11):** Under GDPR Article 17(3), erasure
requests are not absolute — retention may be required by law (tax,
financial) or necessary for the establishment, exercise, or defence of
legal claims (litigation hold). A legal hold placed in response to a
subpoena may need to override an erasure request. The conflict
between erasure and legal hold is **resolved outside Content Cache**
by the administrative workflow that issues both directives. Content
Cache does not adjudicate — it receives the resolved directive and
executes it. The workflow MUST document the resolution and the legal
basis for the decision.

If Commitix declares a 90-day owner retention but the tenant has a
7-year retention policy, the effective retention is 7 years. If the
tenant has no explicit retention policy, the owner retention applies
unchanged.

#### 2.4.2 Legal hold scope (M9)

Legal hold may be applied at three scopes:

-   **Per-artifact:** a specific content artifact is held (e.g.,
    evidence in an investigation).
-   **Per-tenant:** all content for a tenant is held (e.g.,
    regulatory inquiry).
-   **Per-owner:** all content produced by a specific owner/workflow
    is held.

Legal hold is set by an authorized administrative operation, never
automatically by the adapter or by Commitix. The authorization
requirements are:

-   **Role:** The `content.admin.legal_hold` role (or equivalent
    platform-defined administrative role). This role is granted
    explicitly by the platform identity service (Design 1.29).
-   **Scope:** The operation MUST specify the scope (per-artifact,
    per-tenant, or per-owner). The actor MUST be authorized for the
    specified scope.
-   **Audit trail:** Every legal hold operation records: actor
    (principal ID), timestamp, scope (artifact/tenant/owner), action
    (place/release), reason (free-text, required), and before/after
    state. The audit record is written to the Content Cache audit
    log and is immutable.
-   **Release:** Legal hold release requires the same role and scope
    authorization. A legal hold placed at tenant scope cannot be
    released by an actor authorized only for artifact scope.

Legal hold overrides tenant and owner retention and prevents
physical deletion until explicitly released by an authorized
administrator. The conflict between erasure requests and legal hold
is resolved outside Content Cache (see §2.4.1); Content Cache
executes the resolved directive it receives.

#### 2.4.3 Cross-tenant existence leak prevention (C5)

All cross-tenant queries must fail with an **indistinguishable error**.
The Content Cache contract must specify:

-   A digest that exists under another tenant returns the same error
    code and message as a digest that does not exist at all
    (`NOT_FOUND`).
-   The response timing must not differ between "exists under other
    tenant" and "does not exist" — use constant-time comparison or
    equivalent padding.
-   Metadata queries (`exists`, `head`, metadata listing) must be
    tenant-scoped and must not leak shared-backend structure (e.g.,
    bucket layout, shard assignment, storage tier).
-   No response header, error detail, or timing side channel may
    reveal whether a digest exists under a different tenant.

This is not a documentation nicety — it drives the API contract and
must be covered by security negative tests (see §6 Phase 1).

Required lifecycle behavior:

1.  Mark an artifact logically expired/ineligible for normal retrieval
    when its policy requires expiration.
2.  Honor legal hold and applicable retention policy before physical
    disposition.
3.  Account for all live logical references/owners before deleting
    shared physical bytes.
4.  Perform physical deletion asynchronously where necessary and record
    the result.
5.  Verify integrity and reconcile metadata/object-store discrepancies.
6.  Audit administrative access, legal-hold changes, disposition
    decisions, and failures.

Do not physically delete a content-addressed object merely because one
consumer releases its reference. Shared content may have multiple
logical references and different retention requirements.

### 2.5 Storage and language choices

Keep the architecture storage- and language-independent. The proposed Go
service is a candidate implementation, not a platform requirement.
Evaluate it against existing team experience, build/deploy complexity,
SDK maturity, operational support, and the cost of maintaining a second
language. If chosen, record that choice in an ADR after the contract and
first adapter are validated.

The Platform Architecture 1.0 document should only need a small
clarification if necessary: Content Cache 1.26 owns content artifacts; a
Content Service is an implementation option. Do not turn the capstone
architecture into a duplicate service-level design.

See Appendix A for a summary of the language evaluation.

## 3. Proposed Commitix White Paper 1.0 update

The white paper is marked **Final**. Prefer a narrowly scoped, versioned
addendum or a White Paper 1.1 change rather than silently rewriting the
final 1.0 semantics.

Add a section titled **"Optional external payload storage adapter"**
with these constraints:

1.  `commitix-core` continues to expose abstract `Payload` and
    `PayloadSerializer` concepts. It must not depend on the Synanton
    Content Cache client, gRPC, S3, Parquet, PostgreSQL, or a particular
    serialization format.
2.  The existing inline storage adapter remains supported and remains
    the default for the current Phase 1 schema.
3.  An optional Java adapter may externalize payloads above a
    configurable threshold into Content Cache and persist an opaque
    reference plus integrity metadata in `commitix_intents`.
4.  The adapter stores and verifies the serialized payload bytes and
    content type. A reference must identify an immutable payload
    version/digest, not a mutable "latest" pointer.
5.  A remote read failure is an infrastructure/payload-availability
    failure, not a reason to change the immutable intent. Retry behavior
    follows the configured Commitix policy and deadline.
6.  External side effects still require application-level idempotency.
    Content-addressed payload storage does not provide exactly-once
    execution.
7.  This is an optional adapter/phase, not a change to Commitix's
    guarantee: durable intent is guaranteed by the application
    transaction and Commitix storage; successful external execution is
    not.

### 3.1 Idempotency key (C2)

The Content Cache `put` operation MUST be idempotent. The idempotency
key is `(tenant_id, digest)`:

-   If the same tenant publishes the same bytes (same digest) twice,
    the second `put` returns the same artifact reference without
    creating a duplicate object.
-   Different tenants publishing identical bytes get **separate**
    artifact references — no cross-tenant dedup, no existence leak.
-   The key is not a client-generated UUID (retries would create
    duplicates) and not a bare digest (would leak cross-tenant
    existence).
-   Content Cache implements this via a unique constraint on
    `(tenant_id, digest)` in its metadata store, with an upsert
    semantics that returns the existing reference on conflict.

### 3.2 `markForDeletion` semantics (I6)

`markForDeletion` on the Content Cache reference is **not** a delete
request. It is a **reference release**:

-   It signals that "this consumer no longer needs this content."
-   It does not authorize physical deletion.
-   Physical deletion occurs only when **all** of the following hold:
    -   The reference count for this artifact (across all consumers)
        has reached zero.
    -   No retention policy requires the content to be retained.
    -   No legal hold applies.
    -   The artifact's logical expiration has passed (if applicable).
-   Content Cache tracks references via its own reference registry or
    via a callback to Commitix (see §5.4).
-   Until all conditions are met, `markForDeletion` only marks the
    artifact as a **candidate** for eventual deletion.

### 3.3 Timeout, deadline, and slow-path behavior (I2)

The adapter must distinguish "Content Cache is down" from "Content Cache
is slow":

```text
Connection timeout:  2 seconds
Read timeout:        5 seconds per operation
Declare-time total:  10 seconds max for all Content Cache operations
                     during a single declare() call
```

-   If a Content Cache operation exceeds its timeout during `declare()`,
    `declare()` **fails fast** — the intent is not persisted, the
    application transaction is not left in an ambiguous state, and no
    orphan is created (because the intent row is never committed).
-   If Content Cache is slow during **execution** (not declare), the
    adapter applies the configured retry/deadline policy. The intent
    remains durable. The operation may be retried or reach a terminal
    state per Commitix policy.
-   gRPC deadline propagation: the adapter sets a gRPC deadline on every
    Content Cache call that is the minimum of the remaining
    `declare()` deadline and the per-operation read timeout.
-   **Retry interaction (G2):** Retries during `declare()` do **not**
    extend the 10-second budget. All Content Cache operations during a
    single `declare()` call share the same 10-second deadline. If the
    deadline is exhausted, `declare()` fails immediately — no further
    retries are attempted. On execution (not declare), retries are
    bounded by the intent's configured deadline, not by the declare
    deadline.
-   Timeouts are infrastructure failures, not payload availability
    failures. They are not integrity failures.

Add the following row to the failure table in §3.4:

  ------------------------------------------------------------------------
  Failure point                       Required outcome
  ----------------------------------- -----------------------------------
  Content Cache slow (not down)       Timeout before user-facing
                                      deadline; `declare()` fails fast;
                                      no orphan created; execution
                                      retries per policy if already
                                      committed.
  ------------------------------------------------------------------------

### 3.4 Transaction boundary

The integration must preserve the current Commitix contract: `declare()`
persists the intent inside the ambient application transaction; rollback
removes the intent; after-commit notification only accelerates dispatch
and is not the source of durability.

For an external payload, the Content Cache write happens before the
intent row can reference it, because the external service cannot join
the application's database transaction. The documented failure cases
are:

  -----------------------------------------------------------------------
  Failure point                       Required outcome
  ----------------------------------- -----------------------------------
  Content Cache write fails           `declare()` fails; the application
                                      transaction must not commit an
                                      intent referring to missing
                                      content.

  Content Cache write succeeds, then  The payload may be an orphan.
  application transaction rolls back  Content Cache reconciliation
                                      removes it only after a grace
                                      period and after confirming no live
                                      reference exists (see §5.4).

  Application transaction commits     The intent and opaque content
                                      reference are durable together in
                                      the Commitix database. The
                                      referenced immutable payload must
                                      already be readable and
                                      integrity-verified.

  Content Cache becomes unavailable   The intent remains durable;
  during execution                    execution is retried or reaches its
                                      configured terminal/blocking state
                                      according to Commitix policy. Do
                                      not silently substitute an empty
                                      payload.

  Content is corrupt or digest        Fail closed, record a clear
  verification fails                  integrity error, and do not execute
                                      the operation with altered bytes.

  Content Cache slow (not down)       Timeout before user-facing
                                      deadline; `declare()` fails fast;
                                      no orphan created; execution
                                      retries per policy if already
                                      committed.

  Content Cache down during replay    Replay fails; the intent remains
  of already-externalized intent     in its current state; retry when
                                      Content Cache is available. Do
                                      not fall back to inline (no bytes
                                      available).

  Intent reaches terminal state       Retention/disposition follows an
                                      explicit policy. Do not immediately
                                      delete content if executions,
                                      replay, audit, or legal hold still
                                      require it.
  -----------------------------------------------------------------------

This is not a distributed transaction. It is a write-before-reference
protocol with immutable content, idempotent publication, and delayed
orphan reconciliation. Document the limitation explicitly.


## 4. Proposed Commitix implementation-plan updates

The current implementation plan defines `payload_value BYTEA NOT NULL`,
with `PayloadSerializer` storing serialized bytes there. Do not change
this Phase 1 schema implicitly. Add the external payload feature as a
later, opt-in implementation phase after the existing inline contract
and storage adapter tests pass.

### 4.1 Java port

Add an optional port in a module that does not contaminate
`commitix-core` with infrastructure dependencies. Illustrative
interface:

```java
public interface ContentStore {
    ContentReference put(
        byte[] serializedPayload,
        String contentType,
        ContentMetadata metadata
    );

    byte[] get(ContentReference reference);

    boolean exists(ContentReference reference);
}
```

`ContentMetadata` includes `serializer_version` as a required field.
Content Cache uses it to compose the `ContentReference` (see §4.1.1).

`ContentReference` should be an opaque value object containing the
minimum immutable identity/integrity information needed by the adapter.
Avoid exposing backend-specific URI, bucket, Parquet offset, or storage
tier to Commitix domain logic. Retention APIs should be added only when
their lifecycle semantics are agreed with Content Cache; do not equate
`markForDeletion` with permission to physically delete shared content
(see §3.2). The `head()` operation is a future capability (see §5.4)
and is not part of the Phase 1 interface.

The exact Java type and module placement are implementation details to
settle in a Commitix ADR. Keep the core model and public
`Commitix.declare(Intent)` API unchanged if possible.

#### 4.1.1 Content reference format (C4)

`ContentReference` is serialized to the `content_ref TEXT` column in
Commitix and to event envelopes (not payloads — see §5.1). The format
MUST be:

```text
v1:<uri-encoded-fields>
```

-   **Version prefix:** `v1:` allows the format to evolve. Older rows
    with a different prefix are handled by version-specific parsing
    logic. The wire format is **not** part of the public API contract
    and may change between versions.
-   **Logical fields encoded:** `tenant_id`, `artifact_id`, `digest`,
    `generation`, `serializer_version`. The adapter MAY encode additional
    fields (e.g., `content_type`, `size`) but MUST NOT encode
    backend-specific information (bucket, shard, tier).
-   **Serialization:** URI-encoded key=value pairs separated by `&`.
    Example:
    `v1:tenant_id=acme&artifact_id=550e8400-e29b-41d4-a716-446655440000&digest=sha256:abc123&generation=1&serializer_version=3`
-   **Special characters:** Tenant IDs containing `&`, `=`, or other
    URI-reserved characters MUST be percent-encoded. For example, a
    tenant ID `acme&co` is encoded as `acme%26co`. The adapter uses
    standard URI encoding/decoding (RFC 3986).
-   **Maximum length:** 512 characters. A `CHECK` constraint enforces
    this at the database level (see §4.2). This prevents abuse and
    keeps indexes healthy (M5).
-   **Authorization sufficiency:** The reference carries `tenant_id`,
    `artifact_id`, and `digest`. The adapter uses these to authorize
    against Content Cache. No additional out-of-band lookup is required
    for basic get/exists operations.

#### 4.1.2 Authentication and authorization contract (I1)

Commitix authenticates to Content Cache using **mutual TLS (mTLS)**
with workload identity:

-   Each Commitix worker presents a client certificate issued by the
    platform's SPIFFE/SPIRE or equivalent workload identity system.
-   Content Cache verifies the client certificate and extracts the
    workload identity (spiffe://<domain>/ns/<namespace>/sa/<service>).
-   The workload identity determines which Content Cache endpoints the
    adapter may call (capability-based authorization).

Tenant scope is enforced at **three levels**:

1.  **JWT claim (primary):** The adapter obtains a short-lived JWT from
    the platform identity service. The JWT carries a `tenant_id` claim
    that MUST match the tenant scope of the intent being processed.
2.  **Path/header (secondary):** All Content Cache API calls include
    the tenant scope in the request path (e.g.,
    `/v1/tenants/{tenant_id}/artifacts`) or in a `X-Tenant-ID` header.
    Content Cache validates that the JWT `tenant_id` claim matches the
    path/header tenant.
3.  **Reference validation (tertiary):** The `ContentReference` encodes
    the `tenant_id`. The adapter validates that the reference's
    `tenant_id` matches the JWT `tenant_id` before making any Content
    Cache call.

If the credential is valid but the `tenant_id` claim does not match the
reference, the adapter fails closed with an authorization error. This
is a security failure, not a transient error — it is logged and alerted.

Content Cache enforces tenant isolation on every operation (see §2.4.3).

### 4.2 Schema migration

A future migration makes `payload_value` nullable and adds an opaque
reference, digest metadata, and a payload-kind discriminator. The row
must contain **at most one** payload representation: inline bytes, a
content reference, or neither (payload-less intent).

#### 4.2.1 Payload representation invariant (C1)

The migration introduces a `payload_kind` column with three valid values:

```sql
DO $$ BEGIN
    CREATE TYPE payload_kind AS ENUM ('INLINE', 'EXTERNAL', 'NONE');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

ALTER TABLE commitix_intents
    ADD COLUMN payload_kind payload_kind NOT NULL DEFAULT 'INLINE';

ALTER TABLE commitix_intents
    ALTER COLUMN payload_value DROP NOT NULL;

ALTER TABLE commitix_intents
    ADD COLUMN content_ref TEXT,
    ADD COLUMN payload_sha256 BYTEA;

ALTER TABLE commitix_intents
    ADD CONSTRAINT chk_payload_representation CHECK (
        (payload_kind = 'INLINE' AND payload_value IS NOT NULL AND content_ref IS NULL AND payload_sha256 IS NOT NULL)
        OR (payload_kind = 'EXTERNAL' AND payload_value IS NULL AND content_ref IS NOT NULL AND payload_sha256 IS NOT NULL)
        OR (payload_kind = 'NONE' AND payload_value IS NULL AND content_ref IS NULL AND payload_sha256 IS NULL)
    );

ALTER TABLE commitix_intents
    ADD CONSTRAINT chk_content_ref_length CHECK (
        content_ref IS NULL OR length(content_ref) <= 512
    );
```

This replaces the original `chk_payload_exclusive` constraint. The
three-state `payload_kind` enum makes the intent explicit and prevents
the ambiguity of "no payload" vs. "payload not yet decided":

-   `INLINE`: payload bytes are stored in `payload_value`. `content_ref`
    is NULL. `payload_sha256` is always computed and stored for
    integrity verification.
-   `EXTERNAL`: payload bytes are in Content Cache. `content_ref`
    and `payload_sha256` are NOT NULL. `payload_value` is NULL.
-   `NONE`: the intent has no payload (e.g., a pure side-effect like
    "emit a notification using a precomputed template"). Both
    `payload_value` and `content_ref` are NULL. `payload_sha256` is
    NULL.

**DDL ordering (N4):** The migration MUST be applied in the following
order to avoid constraint violations on existing rows:

1.  Add `payload_kind` column with `DEFAULT 'INLINE'` (no table rewrite
    in PostgreSQL 11+).
2.  Add `content_ref` and `payload_sha256` columns (NULL initially).
3.  Backfill `payload_sha256` for all existing rows (see §4.2.2).
4.  Add `chk_payload_representation` and `chk_content_ref_length`
    constraints — **only after** backfill completes. Alternatively,
    add with `NOT VALID` and run `VALIDATE CONSTRAINT` after backfill.

Applying `chk_payload_representation` before backfill fails because
existing rows have `payload_kind = 'INLINE'` (from the DEFAULT) and
`payload_sha256 = NULL` (new column), which violates the INLINE branch
of the constraint.

#### 4.2.2 Migration mechanics (I7)

-   **Existing rows:** All current rows have `payload_value NOT NULL`
    and `payload_kind` defaults to `'INLINE'`. No backfill is needed
    for existing data. The `payload_sha256` column is populated by a
    backfill script that computes SHA-256 over existing `payload_value`
    for all rows where `payload_kind = 'INLINE'`. This backfill runs
    before the new code path is enabled.
-   **Backfill batching (G3):** The backfill script MUST run in
    batches (e.g., 1000 rows per batch) to avoid long-running
    transactions and table locks. It MUST be resumable — if it fails,
    it restarts from the last committed batch (tracked via a
    `backfill_checkpoint` table or similar mechanism). It SHOULD have
    a configurable rate limit to avoid saturating database I/O. The
    backfill progress MUST be observable (e.g., via a gauge metric
    `commitix.payload.backfill.progress`).
-   **Index:** `content_ref` is queried during reconciliation, audit,
    and debugging. Add a B-tree index:

    ```sql
    CREATE INDEX idx_intents_content_ref
        ON commitix_intents USING btree (content_ref)
        WHERE content_ref IS NOT NULL;
    ```

    This is a partial index — it only indexes rows with external
    payloads, keeping the index small.

-   **Rollback:** If the external payload feature is disabled (kill
    switch, see §4.5), the old code path expects `payload_value NOT
    NULL`. Rollback requires either:
    -   **Dual-write:** During rollback, the adapter writes both inline
        and external representations, so old code can read
        `payload_value` while new code uses `content_ref`. This is
        only viable when payloads are below a threshold (e.g., 1 MB) or
        the rollback window is short and payload volume is bounded.
        For large payloads, dual-write will consume significant
        database storage.
    -   **Migration back:** A reverse migration that reads from Content
        Cache and writes to `payload_value` for each externalized
        intent. This consumes database storage during the migration
        window but does not require ongoing dual-write overhead.
    -   **Recommended:** For small payloads or short rollback windows,
        use dual-write during the rollback window, then a cleanup
        migration once all readers are on the old code path. For large
        payloads or extended rollback windows, use the migration-back
        approach.

-   **Schema versioning:** The migration is versioned as
    `V<next>__add_external_payload.sql` in the Commitix Flyway/Liquibase
    migration directory. It is applied atomically with the code deploy.

### 4.3 Serialization and execution path

Proposed path for the opt-in external adapter:

1.  Serialize the `Payload` using the configured `PayloadSerializer`.
2.  Calculate SHA-256 over the exact serialized bytes.
3.  For payloads below the configured threshold, keep the existing
    inline representation (`payload_kind = 'INLINE'`).
4.  For larger payloads, call Content Cache publication with explicit
    tenant/security scope and owner metadata.
5.  Verify the returned immutable reference, digest, content type and
    availability.
6.  Persist the intent plus either inline bytes or the opaque reference
    within the existing application transaction.
7.  Register the existing after-commit dispatch signal; never use the
    signal as a replacement for durable state.
8.  Before invoking the handler, resolve the reference and verify the
    bytes against the stored digest (see §4.3.1 for the verification
    trade-off).
9.  If retrieval fails transiently, apply the configured retry/deadline
    policy. If integrity verification fails, fail closed and expose an
    actionable error.
10. Preserve the intent's immutable operation, payload identity, policy,
    and creation metadata. A replay that intentionally creates a new
    intent must be explicit and must verify the original payload first.

#### 4.3.1 Digest verification trade-off (I8)

Full digest verification on every execution re-reads and re-hashes the
entire payload. For large payloads and high-throughput intents, this
doubles read bandwidth and CPU. Header-only verification protects
against a narrow class of corruption at non-trivial cost. The proposal
therefore adopts a **trust-the-storage-layer** approach:

-   **At publication time:** Full SHA-256 verification. The adapter
    verifies that the bytes returned by Content Cache match the digest
    computed before the `put`. This is the authoritative integrity
    check.
-   **At execution time (default):** No digest verification. The adapter
    trusts the storage layer for bit-rot and truncation detection.
    For S3, this means relying on the object store's own checksums
    (not ETags, which for multipart uploads are a hash-of-hashes, not
    a content hash). For inline PostgreSQL storage, this means relying
    on page checksums. For Parquet, this means relying on the file's
    internal checksums. Content Cache verifies integrity at
    publication time; re-verification on every read is redundant
    given storage-layer checksums. The `get` response MAY include a
    `Content-MD5` or equivalent header so the caller can perform a
    cheap integrity check without re-hashing the full payload — this
    catches read-path bugs without doubling bandwidth.
-   **On replay or audit:** Full digest verification. Replay and audit
    operations always verify the full payload to ensure reproducibility
    and to detect any publication-time verification gaps.
-   **On suspicion (optional):** If the artifact's metadata carries
    an integrity flag indicating prior corruption (set by the integrity
    background job), the adapter performs a full read and SHA-256
    verification before failing closed.

This trade-off is documented in the Commitix ADR. The default is
no read-time verification for performance; full verification is
reserved for replay, audit, and suspicion paths.

#### 4.3.2 Serialization stability (M4)

The `PayloadSerializer` MUST be canonical for a given content type and
serializer version:

-   JSON: sorted keys, no whitespace, UTF-8.
-   Protobuf: deterministic serialization (field ordering by field
    number, no map iteration order).
-   Avro: canonical schema resolution.

If the serializer version changes, the digest changes and dedup breaks.
The adapter MUST include a `serializer_version` field in the
`ContentReference` (see §4.1.1) so that a serializer version change
is detectable without a metadata fetch. Dedup is only guaranteed within
a serializer version.

**Reference composition flow (N29):** Content Cache composes the
`ContentReference`. The adapter passes `serializer_version` inside
`ContentMetadata`, and Content Cache includes it in the returned
reference. The adapter does not compose the reference — it passes
the payload, content type, and metadata to `put()`, and Content
Cache returns the fully-formed `ContentReference`. This keeps the
adapter simple and ensures the reference format is owned by
Content Cache.

If the serializer is not canonical, dedup is not guaranteed and the
adapter MUST disable Content Cache dedup (fall back to unique
artifact IDs per publication).

### 4.4 Retention and orphan reconciliation

Content Cache owns content retention and physical disposition. Commitix
owns intent lifecycle. The integration establishes a reference-lifecycle
contract with the following defaults (I3):

#### 4.4.1 Orphan reconciliation defaults

-   **Grace period:** 24 hours after the Content Cache write. During
    this window, the orphan reconciliation sweep does not consider the
    artifact for deletion.
-   **Cadence:** Hourly sweep. A reconciliation job runs every hour and
    identifies candidate orphans:
    -   Artifacts published to Content Cache but not referenced by any
        committed intent in Commitix.
    -   Artifacts whose referencing intent was rolled back more than
        the grace period ago.
-   **Safe delete rule:** The reconciliation job may mark an artifact
    for deletion only if ALL of the following hold:
    1.  `created_at < now() - 24 hours` (grace period).
    2.  No intent in Commitix references the digest (checked via
        reference inventory, see below).
    3.  No audit hold is active for the artifact.
    4.  No legal hold applies to the artifact, tenant, or owner.
-   **Reference inventory:** Content Cache must be able to answer
    "which owners reference this digest?" This is implemented via:
    -   **Content Cache's own reference registry (required):** The
        adapter calls a `registerReference(tenantId, digest,
        intentId)` API at intent commit time and
        `releaseReference(tenantId, digest, intentId)` at intent
        terminal state. Content Cache maintains a ref count per
        `(tenant_id, digest)` pair. This is the primary and only
        supported mechanism — it preserves tenant isolation because
        Content Cache never queries another tenant's data.
    -   **Per-tenant query API (alternative):** If the reference
        registry is not available, Commitix exposes a per-tenant
        query API: `queryReferences(tenantId, digest) -> List<intentId>`.
        Content Cache calls this API with the tenant scope of the
        artifact being reconciled. Commitix authenticates the call
        and returns only references within the requested tenant. This
        preserves tenant isolation because the query is always
        tenant-scoped and authenticated.
-   **Deletion race prevention:** Before physically deleting an artifact,
    the reconciliation job re-checks the ref count and retention policy.
    If a worker is about to load the payload (detected via an in-flight
    read lock or lease), the deletion is deferred to the next sweep.

#### 4.4.2 Retention propagation

Commitix propagates retention metadata to Content Cache at publication
time:

```text
ContentCache.put(
    tenant_id,
    digest,
    payload_bytes,
    content_type,
    metadata = {
        owner_id:           "<intent_id>",
        owner_type:         "commitix_intent",
        retention_days:     <computed from tenant policy + owner declaration>,
        legal_hold:         false,
        serializer_version: "<version>"
    }
)
```

Content Cache enforces the retention precedence lattice (§2.4.1) using
this metadata. Commitix does not make independent retention decisions
for externalized payloads.

### 4.5 Feature flag strategy (I4)

External payload storage is rolled out incrementally with the following
flag hierarchy:

1.  **Per-tenant flag (`commitix.payload.externalization.tenant.<id>`):**
    Master enable/disable per tenant. Default: disabled. When disabled,
    all intents for this tenant use inline storage regardless of other
    flags.
2.  **Per-intent-type flag
    (`commitix.payload.externalization.intent-type.<type>`):** Enable
    externalization for specific intent types. Default: disabled for
    all types. Allows gradual rollout to non-critical intent types
    first.
3.  **Size threshold flag
    (`commitix.payload.externalization.threshold-bytes`):** Payloads
    above this size are externalized (if the per-tenant and
    per-intent-type flags allow). Default: 64 KiB. Configurable via
    configuration file or runtime config service.
4.  **Kill switch (`commitix.payload.externalization.kill-switch`):**
    Global emergency disable. When activated, all new intents use inline
    storage. Already-externalized intents continue to read from Content
    Cache (read path is not blocked by the kill switch). The kill switch
    is a runtime configuration change, not a deploy.

**Kill-switch limitation (N6):** The kill switch does **not** protect
against Content Cache outages for already-externalized intents. If
Content Cache is down during replay or execution of an externalized
intent, the replay fails — the intent remains in its current state and
is retried when Content Cache is available. The adapter MUST NOT fall
back to inline storage (no bytes are available). This is a genuine
operational failure mode: operators must be aware that activating the
kill switch does not make already-externalized intents resilient to
Content Cache outages.

**Flag semantics (N23, N24):** Flag resolution has two phases:

**Phase 1 — Veto check (kill switch, per-tenant, per-intent-type):**
These three flags are evaluated in order. Each has three states:
`enabled`, `disabled`, or `unset`.

-   If any flag is `disabled`, the chain terminates — the intent uses
    inline storage. This is a veto.
-   If a flag is `enabled` or `unset`, the chain continues to the
    next flag.

**Phase 2 — Threshold trigger:**
If no veto was triggered, the payload size is compared to the
threshold. If `payload_size >= threshold_bytes`, the payload is
externalized. Otherwise, it stays inline.

The threshold flag is the only mechanism that can actively trigger
externalization. The veto flags can only prevent it.

Flag changes are logged with the intent ID, tenant, and resolution path
for audit purposes.

## 5. Integration with Eventing 1.27 and the vertical slice

Content Cache and Commitix integration must preserve Eventing 1.27
semantics:

-   Events are immutable facts; commands express requests.
-   At-least-once delivery is the default; consumers are idempotent.
-   State-changing event producers use transactional-outbox semantics
    where applicable.
-   Tenant, correlation, causation, event identity, and schema version
    travel across the asynchronous boundary.
-   Large payloads are referenced rather than embedded in events.
-   Replay is explicitly classified as safe, conditional, or unsafe.

### 5.1 Event envelope correlation (I9)

When an event carries a Content Cache reference:

-   The reference travels in the **event envelope** (not the payload).
    Specifically, the event schema includes an optional
    `content_reference` field of type `ContentReference` (see §4.1.1
    for the format).
-   The event envelope also carries `tenant_id`, `correlation_id`,
    `causation_id`, and `event_id`. The `ContentReference` encodes
    `tenant_id` — the consumer validates that the reference's
    `tenant_id` matches the event envelope's `tenant_id`.
-   A consumer authorizes a reference received in an event by:
    1.  Validating the reference format and version prefix.
    2.  Checking that the reference's `tenant_id` matches the event
        envelope's `tenant_id`.
    3.  Calling Content Cache with the consumer's own credentials
        (scoped to the same tenant) to retrieve the payload.
-   The reference carries enough information (tenant, artifact, digest)
    to bind it to the event's tenant/causation without requiring a
    separate lookup.
-   **Envelope-only design (N13, M7):** The reference travels in the
    event envelope, NOT in the event payload. This is justified by
    three factors:
    1.  The reference is a routing concern for cross-plane consumers,
        not business data. It tells the consumer where to fetch the
        payload, not what the payload contains.
    2.  The payload schema stays stable across reference format changes.
        If the reference format evolves (v1 → v2), only the envelope
        schema changes — payload consumers are unaffected.
    3.  The envelope is where tenant, correlation, and causation
        already live. The reference MUST be bound to these fields for
        authorization, and co-locating them makes the binding
        explicit and atomic.
    Consumers that process the payload alone (without the envelope)
    do not see the reference. Consumers that need the reference MUST
    process the envelope.
-   If the reference format is unknown (future version prefix), the
    consumer MUST fail with a descriptive error — it MUST NOT attempt
    to parse it as a v1 reference.

A Content Cache reference in an event is not a substitute for the event
envelope or for the source/processing provenance. The event should carry
the stable artifact/generation reference and the identifiers required to
retrieve and authorize it; the payload remains in Content Cache.

Commitix may provide durable execution intent inside a business
application, but it must not silently become the owner of Synanton's
cross-plane workflow contract. The platform integration must state which
work is represented as a Commitix intent and which work is coordinated
under Eventing/Workflow 1.27. Do not duplicate workflow state machines
across both systems without an explicit boundary decision.

### 5.2 Relationship to Commitix deduplication_key (M10)

Commitix already has a `deduplication_key` concept for application-level
idempotency. The Content Cache dedup (C2) is **orthogonal**:

-   **Commitix `deduplication_key`:** Prevents the same intent from
    being processed twice within Commitix. It is a Commitix-internal
    concept, scoped to the application's intent store.
-   **Content Cache dedup `(tenant_id, digest)`:** Prevents the same
    bytes from being stored twice in Content Cache. It is a storage-layer
    concept, scoped to the Content Cache metadata store.
-   **Interaction:** If Commitix deduplicates an intent (same
    `deduplication_key`), the intent is not re-declared, so no new
    Content Cache `put` occurs. If the intent is re-declared (e.g.,
    after a schema change that invalidates the dedup cache), the
    Content Cache dedup ensures the same bytes are not stored twice.
-   They are not the same concept and should not be conflated. The
    Commitix ADR must document this distinction.
-   **Serializer version interaction (M8):** If the same
    `deduplication_key` is used but the serialized payload differs
    (e.g., due to a serializer version change), Commitix dedup does
    not fire (different payload), and Content Cache dedup does not
    fire either (different digest). Both dedups fail independently —
    this is correct behavior. The new intent is stored as a new
    artifact with a new digest.

### 5.3 Content type normalization (M6)

If Content Cache normalizes content types (e.g.,
`application/json; charset=utf-8` vs `application/json`), the
normalization MUST be:

-   Documented in the Design 1.26 API contract.
-   Deterministic (same input always produces the same normalized
    output).
-   Versioned (a normalization rule change is a breaking change).

If normalization is not guaranteed, cross-version reads may fail type
checks. The adapter MUST pass the content type through unchanged and
let Content Cache handle normalization. The adapter MUST NOT normalize
content types itself — normalization is a Content Cache responsibility,
not an adapter responsibility. This ensures a single normalization
authority and avoids adapter/Content Cache normalization mismatches.

### 5.4 Content preview / HEAD (M8)

For DLQ review tooling and debugging, a `head()` operation returning
metadata without fetching the payload is valuable. This is a **future
capability** and is not required for the initial implementation:

-   `head(reference)` returns: `digest`, `content_type`, `size`,
    `created_at`, `retention_metadata`, `provenance`.
-   It does NOT return the payload bytes.
-   It is useful for operators who need to inspect metadata without
    incurring the cost of a full payload fetch.
-   Design 1.26 should include this in the API contract for future
    implementation; the Commitix adapter may add a `head()` method to
    the `ContentStore` interface when needed.

### 5.5 Adapter observability contract (I5)

The adapter MUST emit the following metrics. These are the minimum
for operational readiness and threshold calibration:

  -----------------------------------------------------------------------
  Metric                                       Type        Labels
  -------------------------------------------- ----------- --------------
  `commitix.payload.externalized.count`        counter     intent_type,
                                                          tenant_id

  `commitix.payload.threshold.observed`        histogram   intent_type,
                                                          tenant_id

  `commitix.payload.put.latency`               histogram   tenant_id

  `commitix.payload.get.latency`               histogram   tenant_id

  `commitix.payload.integrity.failures`        counter     tenant_id,
                                                          failure_type

  `commitix.payload.orphan.candidates`         gauge       tenant_id

  `commitix.payload.dedup.hits`               counter     tenant_id

  `commitix.payload.dedup.misses`             counter     tenant_id

  `commitix.payload.backfill.progress`        gauge       tenant_id
  -----------------------------------------------------------------------

-   `commitix.payload.externalized.count` tracks how many intents
    had their payload externalized, by intent type and tenant. This
    is how you know if the threshold is wrong.
-   `commitix.payload.threshold.observed` is a histogram of payload
    sizes at decision time. This is how you calibrate the threshold.
-   `commitix.payload.dedup.hits` and `commitix.payload.dedup.misses`
    together measure per-tenant dedup effectiveness (not cross-tenant
    — see C2). The hit rate is computed by the metrics backend from
    these two counters.

All metrics MUST include `tenant_id` as a label (where applicable)
and `intent_type` where the metric is per-operation. Metrics MUST NOT
include payload bytes, digests of individual payloads, or other
potentially sensitive data.

### Vertical-slice acceptance

The first integrated slice should demonstrate:

1.  One document is ingested and assigned source identity/version.
2.  Content Cache publishes a complete immutable artifact generation
    with digest and provenance.
3.  The event references that generation and propagates
    tenant/correlation/causation identifiers.
4.  Knowledge and Search consume the reference through the contract, not
    storage internals.
5.  A Commitix sample intent can optionally reference a larger
    serialized payload through the Java adapter.
6.  A transaction rollback leaves no committed intent; any prewritten
    unreferenced object is eventually reconciled without deleting a live
    artifact.
7.  Retry, crash recovery, stale worker fencing, and application-level
    idempotency retain their existing Commitix semantics.
8.  Rebuilding derived graph/search state from the same artifact
    generation produces the agreed deterministic outputs.
9.  Tenant-isolation, unauthorized retrieval, integrity mismatch,
    expired content, legal hold, and Content Cache outage cases fail
    safely.
10. Logs/traces join the flow by correlation IDs and do not log payload
    bytes by default.
11. Cross-tenant existence leak tests pass (§2.4.3): a digest from
    another tenant returns an indistinguishable error.
12. Payload-less intents (`payload_kind = 'NONE'`) are accepted and
    processed correctly.

## 6. Recommended rollout

  -------------------------------------------------------------------------
  Phase                   Scope                   Exit criteria
  ----------------------- ----------------------- -------------------------
  0\. Contract alignment  Map proposal to Design  Review-approved contract
                          1.26 and Eventing 1.27; mapping (see Appendix B);
                          clarify ownership and   Design 1.26 owner signs
                          API semantics            off on mapping table;
                          (M3)                    no duplicate authority

  1\. Existing Content    Implement the smallest  Core conformance passes:
  Cache conformance       useful adapter using    identity, put/get, atomic
                          the current contract    publication, integrity,
                          (including C5 cross-   tenant isolation,
                          tenant tests)           authorization,
                                                   provenance, errors,
                                                   capabilities

  2\. Commitix adapter    Define Java             ADR and
  design                  `ContentStore`, opaque  compatibility/migration
                          reference (C4), failure  plan approved
                          semantics (I2, I3),
                          retention handshake
                          (C3), auth contract
                          (I1), observability
                          (I5), feature flags
                          (I4)

  3\. Optional            Add opt-in              Contract tests for
  large-payload adapter   externalization while   commit/rollback, remote
                          preserving inline       outage, checksum failure,
                          behavior; migration     orphan cleanup, retries
                          (C1, I7), execution     and migration rollback;
                          path (I8, M4)           cross-tenant leak
                          (§2.4.3), payload-less
                          intents (§4.2.1)

  4\. Vertical slice      Wire ingestion →        Single scripted
                          Content Cache →         end-to-end path is
                          Eventing →              reproducible; event
                          Knowledge/Search; add   envelope carries
                          Commitix integration    content_reference (I9);
                          test where useful       all 12 acceptance
                                                   criteria pass

  5\. Extended lifecycle  Retention/legal hold,   Extended/Full conformance
                          integrity jobs, orphan  claims backed by tests;
                          reconciliation (I3),   reconciliation sweep
                          object tiers and        verified in staging;
                          production              orphan rate < 0.01%
                          observability            after grace period

  6\. Adoption decision   Decide whether remote   p50/p95/p99 payload size
                          storage becomes a       per intent_type across 30
                          default for any         days (M2); externalization
                          workload                rate; per-tenant dedup
                                                 hit rate;
                                                 latency/cost evidence;
                                                 failure tests;
                                                 operational readiness
  -------------------------------------------------------------------------

Do not begin with Parquet compaction, multi-region storage, 99.95%
availability promises, or production-scale retention jobs. Those are
deployment/service-level commitments and should follow an implemented
contract, measured workload, and explicit conformance target. A PoC must
not claim those SLOs without evidence.

## 7. Document change list

### Synanton Platform

1.  **Design 1.26:** add the reference Content Service adapter proposal,
    artifact identity/provenance mapping, capability/conformance
    expectations, the reference lifecycle contract (§4.4), cross-tenant
    leak prevention (§2.4.3), content preview/HEAD (§5.4), and content
    type normalization (§5.3). Keep storage technology and language
    optional.
2.  **Architecture 1.0:** make at most a small clarification that
    Content Cache 1.26 owns content artifacts and may be implemented by
    a standalone service. Preserve the current authority table and
    implementation sequence. Update the implementation-status row only
    when work actually lands.
3.  **Design 1.27:** add a cross-reference to the Content Cache
    reference semantics for large-payload references (§5.1) and clarify
    that Commitix does not replace the platform event/workflow contract.
4.  **Vertical-slice plan:** add Content Cache artifact-generation
    identity, rollback/orphan reconciliation, and reference-based event
    assertions to the acceptance checklist (§5, acceptance criteria
    6, 11, 12).

### Commitix

1.  **White Paper:** because v1.0 is Final, add a separately reviewed
    addendum or publish v1.1 rather than silently altering the finalized
    guarantee or Phase 1 schema.
2.  **Implementation plan:** retain current inline storage as Phase 1
    baseline; add a future optional external-payload phase and its
    migration/tests.
3.  **Commitix ADR:** decide `ContentStore` module placement, reference
    format (§4.1.1), checksum semantics (§4.3.1), threshold
    configuration, terminal retention, failure classification, auth
    contract (§4.1.2), feature flags (§4.5), and markForDeletion
    semantics (§3.2).
4.  **Schema migration:** version the schema change (§4.2); keep result
    payloads out of scope unless separately approved.
5.  **Tests:** extend the existing `StorageAdapterContractTest` and
    integration suite rather than replacing its transaction, fencing,
    attempt-count, and recovery invariants. Add cross-tenant leak tests
    (§2.4.3), payload-less intent tests (§4.2.1), and orphan
    reconciliation tests (§4.4.1).

## Appendix A: Language evaluation summary

The original draft included a detailed Java/Go/Rust language comparison.
This proposal defers the language decision to a Commitix ADR but
preserves the key considerations here as input to that ADR:

-   **Java (current):** Commitix is a JVM library. Keeping Java avoids a
    second language, leverages existing team expertise, and simplifies
    deployment. The Content Cache client is a Java library in a
    separate module.
-   **Go:** The proposed Content Service is Go. Go is well-suited for
    the Content Cache service (high throughput, low latency, good
    Kubernetes ecosystem). However, maintaining two languages increases
    operational burden. If Go is chosen, the Commitix adapter
    communicates with Content Cache via gRPC/REST, not via a shared
    library.
-   **Rust:** Maximum performance and memory safety, but steepest
    learning curve and longest development time. Not recommended for
    initial implementation.

The ADR should evaluate these options against the criteria in §2.5 and
make a recommendation.

## Appendix B: Contract mapping (Phase 0 artifact)

Phase 0 exit criteria require a "review-approved contract mapping" (M3).
This appendix defines that artifact.

The mapping table maps each proposal section to the Design 1.26 section
it implements or references. The Design 1.26 owner (or delegate) reviews
and signs off on this table before Phase 1 begins.

  -----------------------------------------------------------------------
  Proposal section         Design 1.26 reference   Status
  ------------------------ ----------------------- ------------------
  §2.2 Artifact model      Artifact identity,      Aligned
                           generation, provenance

  §2.3 API requirements    put/get, retrieval,     Aligned
                           tenant isolation,
                           authorization, capabilities

  §2.4.1 Retention lattice Retention, legal hold,   Aligned
                           lifecycle

  §2.4.3 Cross-tenant leak  Tenant isolation,       Aligned
                           security

  §3.1 Idempotency key     Atomic publication     Aligned (with C2
                                                   clarification)

  §4.1.1 Reference format  Content reference       Aligned (with C4
                                                   clarification)

  §4.1.2 Auth contract     Tenant isolation,       Aligned (with I1
                           authorization           clarification)

  §4.2 Schema migration    N/A (Commitix schema)   N/A

  §4.4 Orphan reconcil.    Reference lifecycle     Aligned (with I3
                           defaults)

  §4.4.1 registerReference/  Proposed new Design    New operations
        releaseReference       1.26 §X.Y (Reference   required — needs
                              Registry API)          Design 1.26 owner
                                                   sign-off (N9, N34)

                          Sketch:
                          registerReference(tenantId, digest, ownerId) -> Ack
                          releaseReference(tenantId, digest, ownerId) -> Ack

  §5.1 Event envelope      Large-payload          Aligned (with I9
                           references              clarification)

  §5.4 Content preview      head() operation        Future capability
  -----------------------------------------------------------------------

The Design 1.26 owner confirms that this mapping is accurate and that no
proposal section introduces a new architectural authority.

## References

-   [Synanton Platform Architecture
    1.0](https://raw.githubusercontent.com/synanton/platform/refs/heads/main/docs/architecture/synanton-platform-architecture-1.0.md)
-   [Synanton Design 1.26 --- Content
    Cache](https://raw.githubusercontent.com/synanton/platform/refs/heads/main/docs/architecture/synanton-design-1.26.md)
-   [Synanton Design 1.27 --- Eventing and
    Workflow](https://raw.githubusercontent.com/synanton/platform/refs/heads/main/docs/architecture/synanton-design-1.27.md)
-   [Commitix White Paper
    1.0](https://raw.githubusercontent.com/synanton/commitix/refs/heads/main/docs/Commitix-White-Paper-v1.0.md)
-   [Commitix Phase 1 Implementation
    Plan](https://raw.githubusercontent.com/synanton/commitix/refs/heads/main/docs/implementation.md)

