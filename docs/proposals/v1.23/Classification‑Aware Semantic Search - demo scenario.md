# Classification‑Aware Semantic Search - Demo Scenario

> **Document type:** Demo scenario
> **Title:** Classification‑Aware Semantic Search Demo
> **Status:** Draft
> **Last reviewed:** 2026-08-28
> **Related:** [v1.23 proposal](https://./Synanton_v1.23_Classification_Aware_Semantic_Search.md), [standalone-syntology-demo.md](https://../implementation/demo/standalone-syntology-demo.md)

## 1. Scenario and Roles

**Goal:** Demonstrate that a single document containing three sensitivity classes can be searched by different roles, with each role seeing **only** the sections they are entitled to, and restricted spans (SSN) never stored in any index or cache.

**Fixture:** `demo-data/documents/restricted/employee-jordan.md`

text

```
# Employee File - Jordan Reyes            <- section 1: Identity
SSN: 000-00-0000                             RESTRICTED  → must never be stored
# Contact                                 <- section 2: PERSONAL → HR
Home address: 1 Example Way, Springfield     phone: 555-0100
# Compensation                            <- section 3: FINANCIAL → PAYROLL
| Year | Gross income | Federal tax |         (table → atomic chunk, v1.22 Part X)
```



**Roles:**

| Role                 | Class entitlements    |
| -------------------- | --------------------- |
| `bob` (default user) | `PUBLIC` only         |
| `hr` (gid 4000)      | `PUBLIC`, `PERSONAL`  |
| `payroll` (gid 4100) | `PUBLIC`, `FINANCIAL` |

`hr` and `payroll` are deliberately disjoint so least privilege is visible.

## 2. Prerequisites

- Docker (Cassandra, MinIO, extraction‑gateway, Kafka)
- Java 21
- `cp .env.example .env` and set `SYNANTON_JWT_SECRET`
- `./scripts/run-extract-index-poc.sh` (brings up extraction and indexing stack)
- `./scripts/run-demo.sh` (brings up security/topology/UI stack)

## 3. Setup

**Services:**

| Service            | Port | Role                            |
| ------------------ | ---- | ------------------------------- |
| Cassandra          | 9042 | Chunk storage                   |
| MinIO              | 9000 | Raw document storage            |
| extraction‑gateway | 8080 | v1.21 extraction plane          |
| `synvault`         | 8081 | Content store                   |
| `synflux`          | 8082 | Ingestion pipeline + classifier |
| `synquest`         | 8083 | Search kernel                   |
| `topology`         | 8084 | ACL + class grants              |
| `synapt`           | 8085 | Public API                      |

**Seed data:**

bash

```
# Seed class grants
./scripts/seed-class-grants.sh \
  --role hr --class PERSONAL \
  --role payroll --class FINANCIAL
```



## 4. Configuration

**`synflux` (ingestion + classifier):**

yaml

```
synflux:
  classification:
    enabled: true
    detectors: [ssn, phone, address, table_header]
    policy:
      RESTRICTED: MASK
      PERSONAL: MASK
      FINANCIAL: MASK
    fail_mode: quarantine
```



**`synquest` (search filter):**

yaml

```
synquest:
  classification:
    filter:
      enabled: true
      fail_closed: true
```



**`gateway` (compile‑time injection):**

yaml

```
gateway:
  classification:
    enforce: true
    default_class: RESTRICTED
```



## 5. Walkthrough

| Step | Action                                   | Expected                                                     | Marker                                                |
| ---- | ---------------------------------------- | ------------------------------------------------------------ | ----------------------------------------------------- |
| 1    | Ingest `employee-jordan.md`              | `manifest.state = INDEXED`; chunks have `classification[]`   | `[WORKS]`                                             |
| 2    | Inspect chunks                           | `section_path: ["Employee File - Jordan Reyes", "Contact"]`; table chunk with `structured_content` | `[WORKS]`                                             |
| 3    | Search as `bob` for `"000-00-0000"`      | **Zero hits** (restricted literal never stored)              | `[BLOCKED: detector + masking]`                       |
| 4    | Search as `hr` for `"Springfield"`       | Returns Contact section                                      | `[BLOCKED: class_grants + compile‑time class clause]` |
| 5    | Search as `payroll` for `"Springfield"`  | **Zero hits** (no PERSONAL entitlement)                      | `[BLOCKED: same]`                                     |
| 6    | Search as `payroll` for `"gross income"` | Returns Compensation table                                   | `[BLOCKED: class_grants + compile‑time class clause]` |
| 7    | Search as `hr` for `"gross income"`      | **Zero hits** (no FINANCIAL entitlement)                     | `[BLOCKED: same]`                                     |
| 8    | Revoke `payroll` mid‑session             | Hits disappear within §11 propagation window (p99 < 300ms)   | `[BLOCKED: class propagation over outbox]`            |
| 9    | Inspect Cassandra `chunks`               | `"SSN: [REDACTED:SSN]"` — original literal absent            | `[BLOCKED: masking before commit]`                    |
| 10   | Inspect Kafka `synflux_enriched_chunks`  | Same masked payload                                          | `[BLOCKED: masking before publish]`                   |
| 11   | Inspect `synquest` term dictionary       | `"000-00-0000"` absent from inverted index                   | `[BLOCKED: masking before index]`                     |

## 6. Negative‑Test Commands (Acceptance Assertions)

Run these after ingestion to verify **restricted literals are never stored**:

bash

```
# 1. Cassandra chunks
cqlsh -e "SELECT chunk_text FROM ingestion_cache.chunks WHERE tenant_id='demo';" \
  | grep -q "000-00-0000" && echo "FAIL: SSN in Cassandra" || echo "PASS"

# 2. Kafka payload (dump topic)
kafka-console-consumer --topic synflux_enriched_chunks --from-beginning --max-messages 100 \
  | grep -q "000-00-0000" && echo "FAIL: SSN in Kafka" || echo "PASS"

# 3. Synquest index terms (via admin API)
curl -s http://localhost:8083/admin/terms?tenant=demo | grep -q "000-00-0000" \
  && echo "FAIL: SSN in index" || echo "PASS"

# 4. Embedding cache
cqlsh -e "SELECT chunk_text_hash FROM ingestion_cache.embedding_content_cache WHERE tenant_id='demo';" \
  | grep -q "$(sha256sum <<< 'SSN: 000-00-0000' | cut -d' ' -f1)" \
  && echo "FAIL: SSN hash in embedding cache" || echo "PASS"

# 5. Graph entities
curl -s http://localhost:8084/admin/entities?tenant=demo | grep -q "000-00-0000" \
  && echo "FAIL: SSN in graph" || echo "PASS"
```



All five must print `PASS`.

## 7. What This Demo Proves / Does Not Prove Yet

| Claim                                                        | Proved by                              | Mapped to    |
| ------------------------------------------------------------ | -------------------------------------- | ------------ |
| Chunks carry `section_path`, `page_start`, table `structured_content` | Step 2                                 | v1.22 Part X |
| Restricted literal absent from all stores                    | Steps 3, 9–11, negative tests          | SEC‑2, SEC‑4 |
| `hr` sees PERSONAL, `payroll` does not                       | Steps 4–5                              | SEC‑1, SEC‑3 |
| `payroll` sees FINANCIAL, `hr` does not                      | Steps 6–7                              | SEC‑1, SEC‑3 |
| Revocation propagates within SLO                             | Step 8                                 | SEC‑1 (§11)  |
| Class filter is compile‑time, not post‑filter                | Steps 4–7 (term statistics not leaked) | SEC‑3        |

**Not yet proved (future work):**

- Physical separation per class (SEC‑6) — optional, regulated tenants only
- LLM‑based classification (A4) — not recommended as sole gate
- Pre‑ingest sanitisation of original PDF — out of scope (see §5 of proposal)

## 8. Troubleshooting

| Symptom                      | Likely cause                                | Fix                                                   |
| ---------------------------- | ------------------------------------------- | ----------------------------------------------------- |
| `bob` sees SSN in results    | Detector not enabled or masking not applied | Check `synflux.classification.enabled=true`           |
| `hr` sees no PERSONAL chunks | `class_grants` not seeded                   | Run `./scripts/seed-class-grants.sh`                  |
| Revocation not immediate     | Outbox lag                                  | Check `topology_outbox` table; restart dispatcher     |
| Negative test fails on SSN   | Masking not applied before Cassandra        | Check `synflux.classification.policy.RESTRICTED=MASK` |