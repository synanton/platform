# 07 - gateway - Phase 4 - Compile-Time ACL Injection, Cross-Tenant Synthesis Cache, LLM-Context Sanitisation, Cold-Tier Rehydration

**Version:** 1.0
**Date:** 2026-08-11
**Status:** Draft for review
**Depends on:** Phase 3 `gateway` DoD (reranker port, Resilience4j circuit breakers). Phase 4 `topology` (ACL grants + tier), `security` (`IdpStatusAmortizationCache`), `synquest` (Cuckoo pre-filter), `relix` (MGV + cost profiles), `planner` (region_map + rerank_policy), Redis.
**Scope:** The gateway is the ACL enforcement kernel of the platform. In Phase 4 it: (1) injects ACLs into every retrieval step at compile time (§40 layer 1); (2) trims the final top-N as defence-in-depth (§40 layer 3); (3) runs a cross-tenant synthesis cache in Redis with the cache invariance rule; (4) sanitises LLM context inputs; (5) executes cold-tier rehydration for cold chunks; (6) enforces budget-aware execution and emits the `X-Synanton-Cold-Rehydration` header on degraded serves.

---

## 1. Context and Document Alignment

| Source | Role in this plan |
|--------|-------------------|
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §23 `gateway` (compile-time ACL injection, cross-tenant cache router, LLM-context sanitisation, cold-tier rehydration for synthesis) | Production target |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §40 Three-layer ACL enforcement | Layers 1 and 3 land here |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §38 Redis Keyspaces | Cross-tenant cache + cold rehydration cache keyspaces |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §9 Tier Movement Flow (v1.17 cold retrieval) | Cold rehydration flow |
| [planner Phase 4](./06-planner.md) | Plan JSON now carries `region_map`, `rerank_policy`, `context_budget` |
| [synquest Phase 4](./04-synquest.md) | Pre-filter enabled for HIGH_SECURITY; SearchResponse now carries `acl_prefilter_applied` |
| [relix Phase 4](./05-relix.md) | MGV freshness header; CAS decrement RPC |

**Explicit non-goals for Phase 4:**

- No GPU degraded-mode branching in *synthesis* (Phase 5). Phase 4 gateway consumes `platform_state.gpu_degraded` for read-only tracing, not for model swap.
- No agent-framework tool-loop synthesis (out of scope).
- No cross-region synthesis cache replication (Phase 5) - cache lives in the request's region.

---

## 2. Phase 4 in One Sentence

> Every retrieval step carries injected ACL clauses at compile time; the final synthesis is trimmed as defence-in-depth, cached across tenants with a mathematically invariant key, sourced from a sanitised LLM context, and gracefully degraded when source chunks live in cold storage.

---

## 3. Target Architecture

```mermaid
flowchart TD
  Q[POST /query] --> AUTH[SubjectAssertion from synapt]
  AUTH --> PLAN[Plan from planner<br/>+ region_map + rerank_policy + context_budget]
  PLAN --> ACL[AclInjector.injectMustClauses(plan, subject_id, subject_groups)]
  ACL --> EXEC[DagExecutor runs steps in parallel]
  EXEC --> SEARCH[synquest.search + shard_version_min]
  EXEC --> GRAPH[relix.graph_query]
  SEARCH --> HITS[Hits]
  GRAPH --> SUBG[Subgraph]
  HITS --> TRIM[FinalTrimmer applies AclFilter to top-N]
  TRIM --> CACHEQ[SynthesisCacheKey.compute(caller_acl_mask)]
  CACHEQ --> CACHE[Redis cache.get]
  CACHE -->|hit and mask ⊇ caller| ANSWER1[return cached answer]
  CACHE -->|miss| COLD[ColdRehydrator warms cold chunks]
  COLD --> SAN[LlmContextSanitizer strips systemPromptOverrides]
  SAN --> SYNTH[synanton-llm-client synthesis]
  SYNTH --> STORE[Redis cache.put with acl_mask]
  STORE --> ANSWER2[return + X-Synanton-Cold-Rehydration if applied]
```

---

## 4. Data Contracts

### 4.1 `POST /query` response additions

```json
{
  "answer": "...",
  "citations": [...],
  "hits": [...],
  "graph_result": {...},
  "execution_trace": {
    "plan_id": "...",
    "steps": [...],
    "warnings": [
      { "code": "COLD_SYNTHESIS_DEGRADED", "message": "..." },
      { "code": "MGV_STALE_FALLBACK",    "message": "..." }
    ],
    "region_map": { "synquest": "us-east-1", "synthesis_llm": "us-east-1" }
  },
  "cache_hit": true,
  "acl_layers_applied": ["INJECTION", "PREFILTER", "FINAL_TRIM"]
}
```

New response headers:

```
X-Synanton-Cache-Status: hit|miss|bypass
X-Synanton-Cold-Rehydration: applied|degraded|N/A
X-Synanton-Acl-Layers: 3
```

### 4.2 Synthesis cache key (invariance rule)

```
key = SHA256(
    normalised_query
  || ontology_version
  || synthesis_model_id
  || locale
  || plan.rerank_policy.mode
  || plan.context_budget.total_tokens
  || strip(caller_ACL_field_set)
)
```

`strip(caller_ACL_field_set)` removes the concrete grant IDs so two callers with different but overlapping ACL sets produce the *same* key (subject to §40 synthesis cache invariance). The `acl_mask` stored alongside the value is the intersection `caller_acl ∩ source_doc_acls`. Cache hit requires `caller_acl ⊆ stored.acl_mask`.

### 4.3 Cold rehydration cache

Redis key: `synanton:rehydrate:{sha256(content_ref_id)}`  
TTL: 1 hour  
Value: rehydrated chunk bytes (or `PENDING` sentinel if warmup in flight).

`gateway.cold_rehydration_backoff_seconds=300` prevents repeat storms for the same missing content.

---

## 5. Implementation Design

### 5.1 `AclInjector` (§40 layer 1)

```java
public final class AclInjector {
    Plan inject(Plan plan, SubjectAssertion subject) {
        return plan.mapSteps(step -> switch (step.kind()) {
            case SEARCH -> step.withAclFilter(mustClause(subject));
            case GRAPH  -> step.withAclFilter(subgraphAclClause(subject));
            case LLM    -> step;   // ACL applied at trim time, not model call
        });
    }
    private TermFilter mustClause(SubjectAssertion s) {
        return TermFilter.or(
            TermFilter.term("acl.read_by_subjects", s.subjectId()),
            TermFilter.termsAny("acl.read_by_groups", s.subjectGroups())
        );
    }
}
```

Injected clause travels *inside* the `SearchQuery` protobuf; synquest respects it during BM25/HNSW candidate generation. This is the primary layer - no candidate ever crosses the ACL boundary in memory.

### 5.2 `FinalTrimmer` (§40 layer 3)

Post-fusion, before synthesis:

```java
List<Hit> trim(List<Hit> hits, SubjectAssertion subject) {
    return hits.stream()
        .filter(h -> aclChecker.canRead(subject, h.contentRefId()))
        .toList();
}
```

`AclChecker.canRead(...)` reads from a local Caffeine cache of `topology.grants` (5 s TTL) with sync fallback to `topology` gRPC. Any hit that leaks past layers 1 and 2 is caught here and logged as `gateway_acl_trim_removed_total{tenant}` (should be zero in steady state - alert on non-zero).

### 5.3 `SynthesisCacheRouter`

```java
CacheResult get(SynthesisRequest req) {
    var key = SynthesisCacheKey.compute(req);
    var cached = redis.get(key);
    if (cached != null && subsetOf(req.callerAcl, cached.aclMask)) {
        metric.increment("gateway_cross_tenant_cache_hit_total", "tenant", req.tenant);
        return CacheResult.hit(cached.answer);
    }
    metric.increment("gateway_cross_tenant_cache_miss_total", "tenant", req.tenant);
    return CacheResult.miss(key);
}
void put(String key, SynthesisRequest req, String answer) {
    var mask = intersection(req.callerAcl, req.sourceDocAcls);
    redis.set(key, new CachedAnswer(answer, mask), config.synthesisCacheTtlSeconds);
    metric.increment("gateway_cross_tenant_cache_write_total", "tenant", req.tenant);
}
```

**HIGH_SECURITY gate:** Cross-tenant cache disabled for HIGH_SECURITY (per §41). Config: `gateway.cache.disable_for_high_security=true`. HIGH_SECURITY tenants get a per-tenant scoped cache instead (key prefixed with `tenant:{tenant_id}:`).

**Invalidation:** consumer group `gateway-cache-invalidator` reads `content_events`; for every `CONTENT_UPDATED`/`CONTENT_DELETED`, invalidates all cache entries where `acl_mask` intersects the affected doc's ACL set (best-effort scan; hot invalidation keys are indexed by `content_ref_id → List<cache_key>` in a Redis reverse-index set).

### 5.4 `LlmContextSanitizer`

Two kinds of sanitisation:

1. **Structural (metadata keys/values):**
   - Keys: `[a-zA-Z0-9_-]{1,64}` (rejected otherwise).
   - Values: ≤ 256 chars.
   - Public API can pre-approve keys via `gateway.llm_context.approved_metadata_keys=[...]`.
2. **Prompt-injection defence:**
   - Reject any request that includes a `systemPromptOverrides` field (returns 400 + audit).
   - Strip known jailbreak markers listed in `gateway.llm_context.injection_markers[]` (`"ignore previous instructions"`, `"you are now"`, etc.) from *user-supplied* metadata (not from retrieved chunk bodies - those are quoted with clear boundaries).

Emits `gateway_llm_context_sanitized_total{tenant,reason}`, `gateway_llm_context_injection_rejected_total{tenant}` (alert on rate spike).

### 5.5 `ColdRehydrator`

Called when a retrieved chunk has `manifest.tier IN (COLD, GLACIER)`:

```
key   = "synanton:rehydrate:" + sha256(content_ref_id)
cached = redis.get(key)
if cached is not None and cached != PENDING:
    return cached
if cached == PENDING and backoff_active(content_ref_id):
    return abstract_only(content_ref_id)  // degraded
redis.set(key, PENDING, ttl=cold_rehydration_backoff_seconds)
launch async: synvault.rehydrate(content_ref_id) -> redis.set(key, bytes, 1h)
wait up to gateway.cold_wait_ms (default 8000):
    poll every 100ms
    if resolved: return bytes
if timed_out and gateway.cold_degraded_use_abstract:
    return abstract_only(content_ref_id)
    set X-Synanton-Cold-Rehydration: degraded
```

Metric: `cold_retrieval_triggered_total{tenant,mode}` where `mode ∈ {sync_wait, degraded_serve, background_rehydrate}`.

Alert `ColdSynthesisDegradedRateHigh` fires when `gateway_cold_synthesis_degraded_total / total_synthesis > 0.05` sustained 30 min.

### 5.6 Budget-aware execution

Before starting an expensive plan (LLM synthesis + rerank), check the tenant's remaining budget:

```
remaining = topology.getBudgetRemaining(tenant)
estimated = plan.estimatedCostUnits
if remaining < estimated:
    if strict_budget:
        return 429 with Retry-After
    else:
        drop_optional_steps(plan)  // skip rerank, use cached synthesis, etc.
```

Config: `gateway.budget.strict_default_for_tier: STANDARD=false, HIGH_SECURITY=true, FINANCIAL=true`.

### 5.7 Reranker execution (already existed in Phase 3, extended here)

Consumes `plan.rerank_policy` from planner - no re-decision, just execution:

- `mode=ALWAYS` → call reranker.
- `mode=SCORE_GAP_TRIGGERED` → compute `score_gap = hits[0].score - hits[top_n-1].score`; if `gap < threshold` → call reranker.
- `mode=CALLER_REQUESTED` → planner decided; execute or skip.

Fallback on reranker failure (from Phase 3): return un-reranked hits, `gateway_reranker_fallback_total` increments, response `warnings` includes `RERANK_FALLBACK`. Rerank cache: `gateway.rerank.cache_ttl_seconds=1800`.

---

## 6. Module Boundaries

| Module | Owns in Phase 4 | Does not own |
|---|---|---|
| `gateway` | `AclInjector`, `FinalTrimmer`, `SynthesisCacheRouter`, `LlmContextSanitizer`, `ColdRehydrator`, budget-aware execution, reranker execution | Rerank policy selection (planner); ACL grant storage (topology); Cuckoo filter (synquest) |
| `synquest` | Pre-filter (layer 2) - responds to injected ACL clause | Injection itself |
| `topology` | `getBudgetRemaining`, grant query for `AclChecker` cache warm | Cache invalidation logic |
| `synvault` | Cold rehydration source (async fetch) | Rehydration cache |

---

## 7. Prerequisites

| # | Prereq | Home | Notes |
|---|---|---|---|
| 1 | Redis cluster available | ops | Non-negotiable |
| 2 | Phase 3 reranker port + circuit breakers still in place | phase3/04 | Baseline |
| 3 | `synquest` Cuckoo filter deployed for HIGH_SECURITY tenants (`04-synquest.md`) | phase4 | Non-negotiable |
| 4 | `planner` publishes `rerank_policy`, `context_budget`, `region_map` in plan JSON (`06-planner.md`) | phase4 | Non-negotiable |
| 5 | `synvault` supports async `rehydrate(content_ref_id)` for cold tiers | phase4 (`02-synvault.md`) note | Rehydration endpoint deferred; Phase 4 accepts placeholder returning 501 for missing tier - degraded path exercised |
| 6 | `content_events` topic emitted on every `CONTENT_UPDATED`/`CONTENT_DELETED` | phase3 | Yes |
| 7 | `topology.budget_policy.monthly_usd_cap` and `budget_remaining_current_period` readable via gRPC | `10-topology.md` | Yes |

---

## 8. Task Breakdown

| # | Task | Deliverable | Est. |
|---|---|---|---|
| GW4-1 | Implement `AclInjector`; wire into `DagExecutor` step preparation | Class + tests | 1.5 days |
| GW4-2 | Implement `FinalTrimmer` with local `AclChecker` cache (5 s TTL Caffeine) | Class + tests | 1 day |
| GW4-3 | Implement `SynthesisCacheKey.compute` with ACL strip; property test for invariance | Class + property test | 1 day |
| GW4-4 | Implement `SynthesisCacheRouter` (Redis get/put with acl_mask, subset check) | Class + tests | 1.5 days |
| GW4-5 | HIGH_SECURITY per-tenant cache scope (prefix `tenant:{id}:`) | Config-driven fork | 0.5 day |
| GW4-6 | Implement cache invalidator consumer (`gateway-cache-invalidator` group) with content_ref_id → keys reverse index | Consumer + tests | 1.5 days |
| GW4-7 | Implement `LlmContextSanitizer` (structural + injection markers + `systemPromptOverrides` rejection) | Class + tests | 1 day |
| GW4-8 | Implement `ColdRehydrator` (PENDING sentinel, backoff, degraded serve with abstract) | Class + tests | 1.5 days |
| GW4-9 | Extend budget-aware execution to consult `topology.getBudgetRemaining` and strict mode config | Class + tests | 1 day |
| GW4-10 | Extend reranker executor to consume `plan.rerank_policy` (no re-decision) | Refactor + tests | 0.5 day |
| GW4-11 | Response headers: `X-Synanton-Cache-Status`, `X-Synanton-Cold-Rehydration`, `X-Synanton-Acl-Layers` | Filter | 0.5 day |
| GW4-12 | Response body: `execution_trace.warnings[]`, `cache_hit`, `acl_layers_applied` | DTO update | 0.5 day |
| GW4-13 | Metrics: `gateway_cross_tenant_cache_hit_total`, `gateway_acl_trim_removed_total`, `gateway_llm_context_sanitized_total`, `gateway_llm_context_injection_rejected_total`, `cold_retrieval_triggered_total`, `gateway_cold_synthesis_degraded_total`, `gateway_budget_denied_total` | Micrometer | 0.5 day |
| GW4-14 | Integration test `AclInjectionIT`: revoke user's grant on a doc; next search does not surface it (layer 1) | `AclInjectionIT` | 1 day |
| GW4-15 | Integration test `FinalTrimIT`: seed a bypass hit (mock a synquest response); trim removes it and metric increments | `FinalTrimIT` | 0.5 day |
| GW4-16 | Integration test `CrossTenantCacheIT`: tenant A miss/store; tenant B same query, overlapping ACL → hit; disjoint ACL → miss | `CrossTenantCacheIT` | 1 day |
| GW4-17 | Integration test `LlmInjectionRejectionIT`: request with `systemPromptOverrides` → 400 + audit | `LlmInjectionRejectionIT` | 0.5 day |
| GW4-18 | Integration test `ColdRehydrationIT`: mock cold chunk; verify sync_wait + degraded_serve branches | `ColdRehydrationIT` | 1 day |
| GW4-19 | Integration test `BudgetDeniedIT`: tenant at 100 % of monthly cap → 429 + `Retry-After: 86400` | `BudgetDeniedIT` | 0.5 day |

---

## 9. Testing Strategy

- **Unit:** ACL clause construction (OR of subject + groups). Cache key invariance property (two ACLs with different concrete grants but same intersection → identical key). Cold rehydrator backoff state machine.
- **Integration:** All above `*IT` classes with Testcontainers Postgres + Kafka + Redis. `AclLeakageFuzzIT` — mutate ACL grants in random order under continuous search; assert `gateway_acl_trim_removed_total` remains 0.
- **Regression:** Phase 3 gateway tests (reranker fallback, circuit breaker) unchanged.
- **Security:** `CacheKeyBypassTest` — verify ACL strip cannot be tricked into serving wider results by manipulating request parameters.

---

## 10. Configuration Surface

```yaml
# gateway/src/main/resources/application-phase4.yaml
gateway:
  acl:
    inject: true
    final_trim: true
    checker_cache_ttl_seconds: 5
  cache:
    synthesis:
      enabled: true
      ttl_seconds: 900
      max_entries: 100000
      disable_for_high_security: true
    rerank:
      cache_ttl_seconds: 1800
  llm_context:
    approved_metadata_keys: [locale, source_lang, target_lang, style]
    max_value_length: 256
    reject_system_prompt_overrides: true
    injection_markers:
      - "ignore previous instructions"
      - "you are now"
      - "system prompt:"
      - "disregard the above"
  cold:
    wait_ms: 8000
    rehydration_cache_max_mb: 512
    rehydration_backoff_seconds: 300
    degraded_use_abstract: true
  budget:
    strict_default_for_tier:
      STANDARD: false
      HIGH_SECURITY: true
      FINANCIAL: true
      HEALTHCARE: true
    check_budget_before_synthesis: true
```

---

## 11. Risks and Open Questions

| Risk | Mitigation | Decision |
|---|---|---|
| ACL injection duplicates work already done at synquest layer | Metric `gateway_acl_layers_applied` explicitly tracks 3 layers; profiler shows layer 3 costs < 5 ms | Accepted (defence-in-depth) |
| Cross-tenant cache leak due to subset check bug | `CrossTenantCacheIT` runs exhaustive 3-tenant / 3-doc combinations; property test asserts subset relation strictly | Test |
| Redis cache invalidator falls behind under bursty content updates | Alert `gateway_cache_invalidator_lag_ms > 5000` (page); consumer scaled independently via Kafka Streams | Alert |
| `systemPromptOverrides` bypass via nested JSON | Deep scan (recursive) rather than top-level only; test with 10 nesting depths | Deep scan |
| Cold rehydration flood on tier movement | `rehydration_backoff_seconds` prevents storm; `synflux-router` deferred RECRAWL_BACKGROUND (see `03-synflux-router.md`) reduces cold retrievals during degraded mode | Layered |
| Budget check adds latency per request | Cached via `TenantPolicyCache` (Caffeine 30 s TTL); worst-case 1 gRPC call per 30 s per tenant | Cache |
| `AclChecker` cache staleness lets revoked user see hit briefly | TTL 5 s; combined with synquest Cuckoo at 300 ms p99, window is bounded < 5.3 s | Accepted |

---

## 12. Definition of Done (Phase 4)

1. `POST /query` response includes `acl_layers_applied: ["INJECTION","PREFILTER","FINAL_TRIM"]` for HIGH_SECURITY tenants, `["INJECTION","FINAL_TRIM"]` for STANDARD.
2. `AclLeakageFuzzIT` runs 10K random grant mutations under continuous query load; `gateway_acl_trim_removed_total` remains 0.
3. `CrossTenantCacheIT`: two tenants with overlapping ACL on the same doc share a synthesis cache hit; disjoint ACL always miss.
4. HIGH_SECURITY tenants show `X-Synanton-Cache-Status: bypass` for cross-tenant cache and `hit`/`miss` for per-tenant cache.
5. `LlmInjectionRejectionIT`: request with nested `systemPromptOverrides` returns 400 and produces an audit row; `gateway_llm_context_injection_rejected_total` increments.
6. `ColdRehydrationIT`: cold chunk served within 8 s (sync_wait) or with `X-Synanton-Cold-Rehydration: degraded` and abstract body.
7. `BudgetDeniedIT`: tenant at 100 % cap → HTTP 429 with `Retry-After: 86400`, metric `gateway_budget_denied_total` increments.
8. Reranker policy from plan JSON drives execution; `gateway_reranker_calls_total{outcome="ok"}` > 90 % of search requests for `ALWAYS` policy.
9. All Phase 3 gateway tests pass unchanged.

---

## 13. Follow-on Phases (Signposted)

- **Phase 5** - Cross-region synthesis cache replication with residency guards.
- **Phase 5** - GPU degraded mode synthesis model swap (fallback to smaller model instead of degraded serve).
- **Phase 5** - Streaming answer synthesis with server-sent events; cache streaming continuations.
- **Phase 5** - Agent-framework tool-loop synthesis (multi-turn planning with `synreview` gates).
