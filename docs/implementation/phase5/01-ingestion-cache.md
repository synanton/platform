# 01 - ingestion-cache - Phase 5 - Populate `image_caption_cache`, Per-Tenant Staggered Vacuum, TTLs

**Version:** 1.0
**Date:** 2026-08-11
**Status:** Draft for review
**Depends on:** Phase 2 `ingestion-cache` DoD (`analysis_cache`, `embedding_content_cache`, `image_caption_cache` tables created but unused for images). Phase 5 `synflux` (`03-synflux.md`) is the writer.
**Scope:** Turn the `image_caption_cache` table on for real writes, ship the per-tenant staggered vacuum (v1.17) so nightly cleanup does not spike IOPS platform-wide, and enforce TTLs on every cache.

---

## 1. Context and Document Alignment

| Source | Role in this plan |
|--------|-------------------|
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §18 `ingestion-cache` (embedding cache vacuum, per-tenant staggered vacuum, image_caption_cache) | Production target |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §17 SHA256 incremental cache (v1.1) | Consumer contract from synflux |
| [03-synflux.md](./03-synflux.md) | Writes `image_caption_cache` on vision captioning |

**Explicit non-goals for Phase 5:**

- No cross-tenant cache reuse UI - `cost_privacy.share_image_captions` (default false) remains an ops-level flag.
- No online cache migration between clusters (Phase 6+).

---

## 2. Phase 5 in One Sentence

> Populate `image_caption_cache` on every vision captioning call, run per-tenant staggered vacuum on stable 15-minute slot windows to eliminate platform-wide IOPS spikes, and put explicit TTLs on `analysis_cache`, `embedding_content_cache`, `image_caption_cache`, and `source_digests` so cache growth is bounded.

---

## 3. Data Contracts

### 3.1 `image_caption_cache` schema (Cassandra keyspace `ingestion_cache`)

```cql
CREATE TABLE ingestion_cache.image_captions (
    tenant_id       TEXT,           -- NULL if cross-tenant caching enabled and tenant opts in
    image_sha256    TEXT,
    caption_text    TEXT,
    model_family    TEXT,           -- e.g. "qwen2-vl-7b"
    model_version   TEXT,           -- weight hash / API version
    created_at      TIMESTAMP,
    ttl_expires_at  TIMESTAMP,
    PRIMARY KEY ((tenant_id, image_sha256), model_family)
) WITH default_time_to_live = 31536000;  -- 365 days
```

Cross-tenant reads (when `cost_privacy.share_image_captions = true`): query with `tenant_id = NULL` bucket first, fall back to tenant-scoped bucket. Writes go to the tenant-scoped bucket unless explicit opt-in.

### 3.2 Source digest table

```cql
CREATE TABLE ingestion_cache.source_digests (
    tenant_id       TEXT,
    source_sha256   TEXT,
    content_ref_id  TEXT,
    ingested_at     TIMESTAMP,
    PRIMARY KEY ((tenant_id, source_sha256))
) WITH default_time_to_live = 15552000;  -- 180 days
```

### 3.3 Vacuum coordination table (PostgreSQL, singleton per environment)

```sql
CREATE TABLE ingestion_cache.vacuum_state (
    slot_index          INT PRIMARY KEY,
    slot_start_utc_hour INT NOT NULL,       -- 0..23
    tenant_ids          TEXT[],              -- tenants assigned to this slot
    last_started_at     TIMESTAMPTZ,
    last_completed_at   TIMESTAMPTZ,
    last_iops_pressure  NUMERIC(5,2)
);
```

Slot assignment: `slot_index = xxhash64(tenant_id) mod ingestion_cache.vacuum.slots` (default `slots=96`, giving 15-min windows over 24 h).

---

## 4. Implementation Design

### 4.1 `PerTenantVacuumScheduler`

```java
@Component
public class PerTenantVacuumScheduler {
    void tick() {
        var currentSlot = computeCurrentSlot();
        var tenants = vacuumStateDao.tenantsForSlot(currentSlot);
        for (var batch : partition(tenants, config.concurrentTenantsPerSlot())) {
            if (iopsPressureRatio() > config.maxIopsPressureRatio()) {
                pauseUntil(iopsPressureRatio() < 1.1);
            }
            for (var tenant : batch) {
                vacuumWorker.run(tenant);
            }
        }
    }
}
```

Config:

```yaml
ingestion_cache.vacuum:
  slots: 96
  max_iops_pressure_ratio: 1.3
  concurrent_tenants_per_slot: 8
  stall_after_seconds: 7200
  scan_batch_size: 5000
```

### 4.2 `EmbeddingCacheVacuumWorker`

Per-tenant flow:

1. Build a per-tenant Cuckoo Filter from the tenant's active `chunk_text_hash` rows in `manifest`.
2. Scan `embedding_content_cache` for the tenant in `scan_batch_size` chunks.
3. For each row not in Cuckoo → DELETE (or CQL TTL-expire directly if TTL policy suffices).
4. Update `last_used_at` debouncing: `≤ once per 5 min per row` (async fire-and-forget through Cassandra async statement executor).
5. On completion, write `vacuum_state.last_completed_at`.
6. Metrics: `vacuum_progress_ratio{tenant}`, `vacuum_last_completed_at{tenant}`, `vacuum_iops_pressure_ratio`.

Alerts:

- `VacuumStalled` - `now() - last_started_at > stall_after_seconds` (page).
- `VacuumIopsPressureHigh` - `vacuum_iops_pressure_ratio > 1.3` sustained 30 min (warn).

### 4.3 TTL enforcement

Every cache table now carries `default_time_to_live`:

| Cache | TTL | Rationale |
|---|---|---|
| `analysis_cache` | 90 days | Chain-of-thought Pass 1 rarely reused after 90 d; synreview may need history longer via S3 audit |
| `embedding_content_cache` | 180 days | Cost of re-embed vs storage cost |
| `image_caption_cache` | 365 days | Images stable; captions expensive to regenerate |
| `source_digests` | 180 days | Prevents re-ingest of unchanged source; older docs unlikely to be replayed |

TTL is a *storage-level* fallback. Vacuum is the *correctness* mechanism (removes orphans). Both run.

### 4.4 Cross-tenant image caption reuse (opt-in)

- `cost_privacy.share_image_captions` in `topology.tenant_policy` (default false).
- When true: writes also populate `image_captions` with `tenant_id=NULL`.
- Reads: check `tenant_id=NULL` bucket first; on miss, tenant-scoped bucket.
- Cross-tenant hit metric: `ingestion_cache_image_cross_tenant_hit_total{consumer_tenant}`.
- HIGH_SECURITY tenants: cross-tenant reuse always disabled regardless of flag.

---

## 5. Module Boundaries

| Module | Owns in Phase 5 | Does not own |
|---|---|---|
| `ingestion-cache` | Schemas + TTL + vacuum worker + slot scheduling + Cuckoo dedup filter | Writing image captions (`synflux` does); reading TTL-expired rows (they are gone) |
| `synflux` | Writing `image_captions` rows | Vacuum |
| `topology` | `cost_privacy.share_image_captions` flag | Enforcement (ingestion-cache reads) |

---

## 6. Prerequisites

| # | Prereq | Home | Notes |
|---|---|---|---|
| 1 | Phase 2 `image_caption_cache` table existed but unused | phase2 | Non-negotiable |
| 2 | Cassandra keyspace `ingestion_cache` migration V3 (add TTLs, add `model_family` column, add `tenant_id=NULL` bucket) | Flyway/CQL | Yes |
| 3 | Cuckoo filter dependency in ingestion-cache library BOM | shared | Yes (also used by synquest Phase 4) |
| 4 | `topology.tenant_policy.cost_privacy` schema | phase4/10-topology | Yes |
| 5 | Prometheus scraping ingestion-cache (from Phase 4 observability stack) | phase4/15 | Yes |

---

## 7. Task Breakdown

| # | Task | Deliverable | Est. |
|---|---|---|---|
| IC5-1 | CQL migration V3: TTLs on all caches; `model_family` column on image_captions; `tenant_id=NULL` bucket support | Migration files | 1 day |
| IC5-2 | PostgreSQL migration: `ingestion_cache.vacuum_state` table + seed slot assignments | Flyway | 0.5 day |
| IC5-3 | Implement `PerTenantVacuumScheduler` with slot arithmetic + IOPS-pressure gating | Class + tests | 1.5 days |
| IC5-4 | Implement `EmbeddingCacheVacuumWorker` (Cuckoo build + orphan delete + debounced last_used_at) | Class + tests | 2 days |
| IC5-5 | Wire cross-tenant image caption reuse (opt-in) with HIGH_SECURITY override | Class + tests | 1 day |
| IC5-6 | Expose `image_captions` DAO methods (`putCaption`, `findCaption`, `findCaptionCrossTenant`) | DAO + tests | 0.5 day |
| IC5-7 | Metrics: `ingestion_cache_image_cache_hit_total`, `vacuum_progress_ratio`, `vacuum_last_completed_at`, `vacuum_iops_pressure_ratio` | Micrometer | 0.5 day |
| IC5-8 | Integration test `PerTenantVacuumIT` (Testcontainers): 100 tenants → each cleans in its slot without global IOPS spike | `PerTenantVacuumIT` | 1 day |
| IC5-9 | Integration test `ImageCaptionCacheIT`: put/get + cross-tenant opt-in + HIGH_SECURITY override | `ImageCaptionCacheIT` | 0.5 day |
| IC5-10 | Chaos test `VacuumStallDetectionIT`: force a stalled slot; `VacuumStalled` alert fires | `VacuumStallDetectionIT` | 0.5 day |

---

## 8. Testing Strategy

- **Unit:** Slot arithmetic. Cuckoo dedup correctness. TTL calculation.
- **Integration:** All `*IT` classes with Testcontainers Cassandra + Postgres.
- **Load:** `IopsSpikeAvoidanceLoadTest` - simulate 100-tenant vacuum window; assert peak IOPS < 1.3× baseline.
- **Regression:** Phase 2 cache put/get tests unchanged.

---

## 9. Configuration Surface

```yaml
# ingestion-cache/src/main/resources/application-phase5.yaml
ingestion_cache:
  vacuum:
    enabled: true
    slots: 96
    max_iops_pressure_ratio: 1.3
    concurrent_tenants_per_slot: 8
    stall_after_seconds: 7200
    scan_batch_size: 5000
    last_used_at_debounce_seconds: 300
  ttl:
    analysis_days: 90
    embedding_days: 180
    image_caption_days: 365
    source_digest_days: 180
  image_cache:
    cross_tenant_reuse_default: false
    high_security_disables_reuse: true
```

---

## 10. Risks and Open Questions

| Risk | Mitigation | Decision |
|---|---|---|
| Cuckoo filter memory spike for tenants with 100M+ chunks | Rotation schedule per §18 (weekly per partition for large tenants); documented | Rotation |
| TTL expiry deletes actively-used rows during vacuum | `default_time_to_live` is a floor; vacuum only removes explicit orphans (not-in-Cuckoo); TTL prunes truly cold entries | Layered |
| Cross-tenant reuse leaks IP through captions | Opt-in per tenant, disabled for HIGH_SECURITY, audit event on every cross-tenant hit | Audit |
| Slot boundary drift on clock skew | Slots computed from wall-clock hour; NTP required (operator prereq) | Doc |
| Vacuum blocks read traffic at wrong hour | Slot windows narrow (15 min); backpressure gates further work | Bounded |

---

## 11. Definition of Done (Phase 5)

1. `image_captions` table receives writes from `synflux` on the first vision-enabled ingest run; `ingestion_cache_image_cache_hit_total` increments on the second run over duplicate images.
2. `PerTenantVacuumIT` passes: 100 tenants complete their vacuum in their assigned slots; global IOPS peak < 1.3× baseline.
3. `vacuum_progress_ratio{tenant}` and `vacuum_last_completed_at{tenant}` visible in Grafana for every tenant.
4. `VacuumStalled` alert wired and demonstrated to fire under simulated stall.
5. Cross-tenant image caption reuse honours `cost_privacy.share_image_captions`; HIGH_SECURITY tenant never reuses regardless of flag.
6. TTLs set on all four caches; `nodetool` verifies `default_time_to_live` per table.
7. Phase 2 cache tests pass unchanged.

---

## 12. Follow-on Phases (Signposted)

- **v1.20+** - Cross-cluster cache replication for read-nearest.
- **v1.20+** - Adaptive TTL tuning per tenant based on hit-rate history.
- **v1.20+** - Dedicated compaction schedule for `image_captions` (large partitions).
