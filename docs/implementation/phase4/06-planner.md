# 06 - planner - Phase 4 - Cross-Region Penalty, Follow-the-Sun, Context Budget, Rerank Policy

**Version:** 1.0
**Date:** 2026-08-11
**Status:** Draft for review
**Depends on:** Phase 3 `planner` DoD (cost estimation, multi-plan generation, cheapest-plan selection). Phase 4 `topology` (`cross_region_penalty_ms` JSONB), `control-plane` (extended `ModelServingDirectory`), `relix` (calibrated cost profiles).
**Scope:** Turn the planner into a *residency- and region-aware* planner. Use per-tenant cross-region penalty maps for shard/model selection, honour follow-the-sun replica placement for stateless inference, allocate a per-query context budget by intent class, and pick a rerank policy per tenant.

---

## 1. Context and Document Alignment

| Source | Role in this plan |
|--------|-------------------|
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §22 `planner` (cross-region penalty map, follow-the-sun, context budget v1.1, RRF) | Production target |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §43 Cross-Region & Data Residency (latency map, follow-the-sun) | Data source for penalty map |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §27 `ModelServingDirectory` | Provides `(model_family, region) → replicas[]` resolution |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §30 Reranker Port | Rerank policy consumed by gateway |
| [05-relix.md](./05-relix.md), [04-synquest.md](./04-synquest.md) | Emit calibrated cost profiles and shard descriptors the planner consumes |

**Explicit non-goals for Phase 4:**

- No automatic rerank policy tuning per tenant. Policy is set explicitly in `topology.tenant_policy.rerank_policy`.
- No deep-research plan generation (Phase 5).
- No planner-side residency *enforcement* - planner *prefers* in-region resources but the final refusal happens in `synvault` (writes) and `synquest` (reads via shard descriptors filtered by planner earlier).

---

## 2. Phase 4 in One Sentence

> Route every plan to the cheapest healthy replica that meets the tenant's residency policy, use per-tenant penalty maps (with a bootstrapped platform default) to score cross-region hops, allocate context tokens by intent class, and pick a rerank policy per tenant with a documented fallback.

---

## 3. Target Architecture

```mermaid
flowchart TD
  REQ[POST /plan] --> INTENT[IntentClassifier]
  INTENT --> BUDGET[ContextBudgetAllocator]
  INTENT --> GEN[PlanGenerator produces N candidate plans]
  GEN --> SCORER[PlanScorer]
  SCORER --> RES[ResidencyFilter drops plans that would leave tenant regions]
  RES --> COST[CostEvaluator uses calibrated relix costs + cross_region_penalty_ms]
  COST --> RERANK[RerankPolicySelector picks per-tenant policy]
  RERANK --> RETURN[Cheapest legal plan]
  MSD[ModelServingDirectory] -.->|(model_family, region) → replicas[]| COST
  CRPM[topology.cross_region_penalty_ms] -.->|per-tenant| COST
  SHARD_DESC[synquest.CapabilityPort.descriptor()] -.->|region, cuckoo_enabled| RES
```

---

## 4. Data Contracts

### 4.1 `POST /plan` request/response additions

Request (superset of Phase 3):

```json
{
  "query": "...",
  "tenant_id": "demo",
  "subject_id": "user:alice",
  "session_affinity_key": "sess-abc123",  // optional; enables follow-the-sun stateful pinning
  "caller_regions": ["us-east-1"],         // client hint; used for tie-breaking
  "rerank_hint": "CALLER_REQUESTED"        // optional; overrides tenant default only if policy=CALLER_REQUESTED
}
```

Response (superset of Phase 3):

```json
{
  "plan_id": "...",
  "intent": "SYNTHESIS",
  "context_budget": {
    "total_tokens": 32000,
    "allocation": { "chunks": 17600, "graph": 4800, "history": 4800, "system": 4800 }
  },
  "steps": [ ... ],
  "region_map": {
    "synquest": "us-east-1",
    "reranker": "us-east-1",
    "synthesis_llm": "us-east-1"
  },
  "cross_region_penalty_ms_total": 12,
  "rerank_policy": {
    "mode": "SCORE_GAP_TRIGGERED",
    "model_family": "bge-reranker-large",
    "candidate_pool_size": 100,
    "top_n": 20
  },
  "estimated_cost_units": 47.3
}
```

### 4.2 `topology.cross_region_penalty_ms` schema (referenced from `10-topology.md`)

Per-tenant JSONB:

```json
{
  "us-east-1": { "us-west-2": 60, "eu-west-1": 90 },
  "us-west-2": { "us-east-1": 60, "ap-southeast-1": 130 }
}
```

Fallback resolution order:

1. Per-tenant override (from `topology.tenant_policy.cross_region_penalty_ms`).
2. Platform default (bootstrapped hourly from p95 RTT samples via `ModelHealthProber`).
3. Scalar fallback `50 ms` (v1.16 compat) if neither exists.

### 4.3 `topology.rerank_policy` per tenant

```json
{
  "mode": "ALWAYS" | "SCORE_GAP_TRIGGERED" | "CALLER_REQUESTED",
  "model_family": "bge-reranker-large",
  "candidate_pool_size": 100,
  "top_n": 20,
  "score_gap_threshold": 0.1   // only for SCORE_GAP_TRIGGERED
}
```

### 4.4 Context budget defaults (per §22 v1.1)

| Intent | Total tokens (STANDARD tier) | Chunks | Graph | History | System |
|---|---|---|---|---|---|
| LOOKUP | 32K | 70 % | 5 % | 5 % | 15 % |
| SYNTHESIS | 32K | 55 % | 15 % | 15 % | 10 % |
| RESEARCH | 32K | 40 % | 25 % | 10 % | 15 % |

`HIGH_SECURITY` tier defaults to 16K total. Hard cap: `planner.context_budget.hard_cap_tokens=1_000_000`.

---

## 5. Implementation Design

### 5.1 `ContextBudgetAllocator`

```java
public final class ContextBudgetAllocator {
    ContextBudget allocate(Intent intent, TenantPolicy policy) {
        int total = policy.maxContextTokens != null ? policy.maxContextTokens : defaultsFor(policy.tier);
        return switch (intent) {
            case LOOKUP    -> new ContextBudget(total, 0.70, 0.05, 0.05, 0.15);
            case SYNTHESIS -> new ContextBudget(total, 0.55, 0.15, 0.15, 0.10);
            case RESEARCH  -> new ContextBudget(total, 0.40, 0.25, 0.10, 0.15);
        };
    }
}
```

Downstream (`gateway`) truncates each context slot to its allocation; emits `planner_budget_truncated_total{tenant,intent,phase}` when truncation actually occurs.

### 5.2 `ResidencyFilter`

```java
public List<Plan> filter(List<Plan> plans, TenantPolicy policy) {
    return plans.stream()
        .filter(p -> p.stepRegions().stream().allMatch(r -> policy.allowedRegions().contains(r)))
        .toList();
}
```

If `filter(...)` returns empty, planner returns HTTP 422 `no_plan_meets_residency` (mirrors `synvault` refusal).

### 5.3 `CostEvaluator` (extended)

Per plan, per step:

```
step_cost = base_cost(step_kind)
          + connector_cost_p99_ns(connector, pattern) * ns_to_units
          + cross_region_penalty_ms(caller_region, step_region) * penalty_weight
plan_cost = sum(step_cost) + rerank_cost_if_any + synthesis_cost
```

`connector_cost_p99_ns` is fetched from `RelixCatalog.getCost` (see `05-relix.md`). `cross_region_penalty_ms` is looked up via `TenantPolicyCache.getCrossRegionPenaltyMap(tenantId)`, falling back per §4.2.

Weights: `planner.cost.penalty_weight_per_ms = 1.0` (units are commensurable with connector cost); tunable via config.

### 5.4 `FollowTheSunReplicaSelector`

For each stateless model call (embed, rerank):

```
replicas = ModelServingDirectory.get(model_family, allowed_regions)
healthy  = filter(replicas, r.status == HEALTHY)
pick     = argmin(healthy, r.region → cross_region_penalty[caller_region])
```

For stateful sessions (given `session_affinity_key` in the request):

```
pinned = ReplicaAffinityCache.get(session_affinity_key)
if pinned is not None and pinned.age < planner.follow_the_sun.session_affinity_ttl_seconds (default 900):
    return pinned
else:
    pick as stateless; cache under key
```

Session pin published as response header `X-Synanton-Session-Affinity: <replica_id>`.

### 5.5 `RerankPolicySelector`

```
tenant_policy = topology.getRerankPolicy(tenant_id)  // default: ALWAYS with bge-reranker-large
if tenant_policy.mode == CALLER_REQUESTED:
    apply_rerank = (request.rerank_hint == "ON")
elif tenant_policy.mode == ALWAYS:
    apply_rerank = true
elif tenant_policy.mode == SCORE_GAP_TRIGGERED:
    apply_rerank = deferred to gateway (needs actual retrieved hits' score gap)
```

Planner emits the *decision* (apply / defer / skip) so gateway does not re-evaluate policy - it just executes.

### 5.6 Planner-side reranker cache warmup hint

If the same query fingerprint appeared in the last 30 min (planner tracks a small LRU of recent normalised queries), planner sets `cached_rerank_likely=true` in the plan, letting gateway skip candidate generation cost.

---

## 6. Module Boundaries

| Module | Owns in Phase 4 | Does not own |
|---|---|---|
| `planner` | `ContextBudgetAllocator`, `ResidencyFilter`, `CostEvaluator`, `FollowTheSunReplicaSelector`, `RerankPolicySelector`, plan JSON shape | The reranker itself (gateway); the residency policy source (topology); the connector cost source (relix) |
| `topology` | `cross_region_penalty_ms`, `rerank_policy`, `max_context_tokens` fields on `tenant_policy` | Cost model |
| `control-plane` | `ModelServingDirectory` + `ModelHealthProber` bootstrapping penalty map hourly | Planner integration |
| `gateway` | Executes plan's rerank decision; applies context budget truncation | Selecting the policy |

---

## 7. Prerequisites

| # | Prereq | Home | Notes |
|---|---|---|---|
| 1 | `topology.tenant_policy` has `cross_region_penalty_ms`, `rerank_policy`, `max_context_tokens` fields | `10-topology.md` | Non-negotiable |
| 2 | `ModelServingDirectory` returns `List<Replica>` with `region`, `status`, `model_family` | `11-control-plane.md` | Extend from Phase 3 |
| 3 | `RelixCatalog.getCost(connector, pattern)` returns per-bin p99 within 90 s of connector traffic | `05-relix.md` | Non-negotiable |
| 4 | `synquest.CapabilityPort.descriptor()` publishes `region`, `shard_version`, `cuckoo_enabled` | `04-synquest.md` | Yes |
| 5 | `ModelHealthProber` bootstraps platform-wide penalty map hourly (from p95 RTT between shards) | `11-control-plane.md` | Yes |

---

## 8. Task Breakdown

| # | Task | Deliverable | Est. |
|---|---|---|---|
| PL4-1 | Implement `ContextBudgetAllocator` per §22 v1.1 tables | Class + unit tests | 0.5 day |
| PL4-2 | Implement `ResidencyFilter`; wire into plan generation pipeline | Class + tests | 0.5 day |
| PL4-3 | Implement `TenantPolicyCache` (Caffeine + `topology_events` invalidation) for policy, penalty map, rerank policy | Class + tests | 1 day |
| PL4-4 | Implement penalty map resolution (per-tenant → platform default → scalar fallback) | Class + tests | 0.5 day |
| PL4-5 | Extend `CostEvaluator` with connector cost + cross-region penalty terms | Refactor + tests | 1 day |
| PL4-6 | Implement `FollowTheSunReplicaSelector` (stateless + affinity-pinned stateful) | Class + tests | 1.5 days |
| PL4-7 | Implement `ReplicaAffinityCache` (Caffeine, TTL 900 s) + `X-Synanton-Session-Affinity` header | Class + header wiring | 0.5 day |
| PL4-8 | Implement `RerankPolicySelector`; encode decision in plan JSON | Class + tests | 0.5 day |
| PL4-9 | Extend `POST /plan` response JSON with `context_budget`, `region_map`, `cross_region_penalty_ms_total`, `rerank_policy` | DTO update + snapshot test | 0.5 day |
| PL4-10 | Metrics: `planner_context_tokens_used{tenant,intent}`, `planner_budget_truncated_total{tenant,intent,phase}`, `planner_cross_region_penalty_ms{tenant,from,to}`, `planner_rerank_decision_total{tenant,mode,decision}` | Micrometer | 0.5 day |
| PL4-11 | Integration test `ResidencyFilterIT`: tenant with `allowed=[us-east-1]`; only in-region shards + replicas selected | `ResidencyFilterIT` | 0.5 day |
| PL4-12 | Integration test `FollowTheSunIT`: two healthy replicas in different regions; caller in us-east-1 → us-east-1 replica picked | `FollowTheSunIT` | 0.5 day |
| PL4-13 | Integration test `ContextBudgetIT`: SYNTHESIS intent → 55/15/15/10 split applied | `ContextBudgetIT` | 0.5 day |
| PL4-14 | Integration test `RerankPolicyIT`: policy=CALLER_REQUESTED without hint → no rerank | `RerankPolicyIT` | 0.5 day |
| PL4-15 | Regression: Phase 3 `PlannerPlanComparisonIT` (cheapest-plan selection) still passes | - | 0.25 day |

---

## 9. Testing Strategy

- **Unit:** Budget allocator math. Penalty map resolution fallback chain. Rerank policy truth table. Follow-the-sun replica ordering under mixed health states.
- **Integration:** `ResidencyFilterIT`, `FollowTheSunIT`, `ContextBudgetIT`, `RerankPolicyIT`, `PenaltyMapCacheInvalidationIT` (`RESIDENCY_UPDATED` event → cache refresh).
- **Property:** `CostEvaluatorMonotonicityPT` - adding a step never decreases plan cost; increasing penalty for a region never decreases cost.
- **Regression:** Phase 3 plan comparison and cost estimation tests unchanged.

---

## 10. Configuration Surface

```yaml
# planner/src/main/resources/application-phase4.yaml
planner:
  context_budget:
    default_lookup_tokens: 32000
    default_synthesis_tokens: 32000
    default_research_tokens: 32000
    high_security_tier_tokens: 16000
    hard_cap_tokens: 1000000
  follow_the_sun:
    enabled: true
    session_affinity_ttl_seconds: 900
  cost:
    penalty_weight_per_ms: 1.0
    scalar_fallback_penalty_ms: 50
  rerank:
    default_mode: ALWAYS
    default_model_family: bge-reranker-large
    default_candidate_pool_size: 100
    default_top_n: 20
    default_score_gap_threshold: 0.1
  policy_cache:
    ttl_seconds: 60
    max_size: 4096
  rrf:
    k: 60
    weights:
      lexical: 1.0
      semantic: 1.0
      graph: 0.8
```

---

## 11. Risks and Open Questions

| Risk | Mitigation | Decision |
|---|---|---|
| Platform-default penalty map bootstrap depends on `ModelHealthProber` uptime | Fallback to scalar `50 ms` if map missing; alert `planner_penalty_map_missing` if platform default is stale > 6 h | Alert |
| Session affinity pins to a degraded replica | On replica health transition to `UNAVAILABLE`, evict affinity cache entries mentioning it; next request re-picks | Add eviction |
| Rerank policy mode `SCORE_GAP_TRIGGERED` requires gateway to know threshold | Threshold plumbed via plan JSON (`rerank_policy.score_gap_threshold`) so gateway does not re-lookup | Included in plan |
| Context budget over-truncates and starves synthesis | Emit `planner_budget_truncated_total` metric; alert `planner_high_truncation_rate` (> 10 % of queries for 1 h → warn) | Alert |
| Residency filter drops all plans on misconfigured tenant policy | Return HTTP 422 with structured error; ops runbook explains diagnosis | Runbook |
| Cross-region penalty weight `1.0` mixes with connector `ns` costs uncomfortably | Both normalized to same-magnitude "cost units" via `ns_to_units = 1e-6` (1 unit ≈ 1 ms); assert in unit test | Assertion in test |

---

## 12. Definition of Done (Phase 4)

1. `POST /plan` response includes `context_budget`, `region_map`, `cross_region_penalty_ms_total`, `rerank_policy` fields.
2. `ResidencyFilterIT`: tenant with `allowed=[us-east-1]` produces plans referring exclusively to `us-east-1` regions; when only cross-region resources exist, returns 422.
3. `FollowTheSunIT`: caller in `us-east-1` with equal-health replicas in `us-east-1` and `eu-west-1` picks `us-east-1`.
4. `ContextBudgetIT`: SYNTHESIS intent allocates 17600 / 4800 / 4800 / 4800 (55/15/15/10 of 32K).
5. `RerankPolicyIT`: CALLER_REQUESTED policy without hint yields `rerank_policy.mode="CALLER_REQUESTED"` and no rerank step in plan; with hint=ON, rerank step appears.
6. `planner_cross_region_penalty_ms{tenant,from,to}` histogram visible in Grafana.
7. `PenaltyMapCacheInvalidationIT` passes: policy change → next request uses new map within 1 s.
8. Phase 3 cheapest-plan tests unchanged.

---

## 13. Follow-on Phases (Signposted)

- **Phase 5** - Deep-research plan template (multi-hop, HITL gate via `synreview`).
- **Phase 5** - Automatic per-tenant `rerank_policy` tuning based on observed score gap distributions.
- **Phase 5** - Cross-region latency probing done by planner itself (removes hourly bootstrap dependency).
- **Phase 5** - Region-aware caching hints so gateway pre-warms synthesis cache before high-load windows.
