# Classification‑Aware Semantic Search - Demo Scenario

> **Document type:** Demo scenario
> **Title:** Classification‑Aware Semantic Search Demo
> **Status:** Draft
> **Last reviewed:** 2026-08-29
> **Related:** [synanton-design-1.23.md](../architecture/synanton-design-1.23.md), [v1.23 implementation plan](../implementation/classification-aware-search/INDEX.md), [standalone-syntology-demo.md](../implementation/demo/standalone-syntology-demo.md)

## 1. Scenario and Roles

**Goal:** Demonstrate that a single document containing three sensitivity classes can be searched by different roles, where each role sees the classified sections but only class-authorized roles see the **original** sensitive values within them — except SSN, which is configured so its original is never stored for anyone. See [synanton-design-1.23.md](../architecture/synanton-design-1.23.md) §3.2a for the underlying masking-outcome/representation model.

**Fixture:** `demo-data/documents/restricted/employee-jordan.md`

text

```
# Employee File - Jordan Reyes            <- section 1: Identity
SSN: 000-00-0000                             RESTRICTED, store_original:false → masked-only, for everyone
# Contact                                 <- section 2: PERSONAL, store_original:true → original for HR, masked for everyone else
Home address: 1 Example Way, Springfield     phone: 555-0100
# Compensation                            <- section 3: FINANCIAL, store_original:true → original for PAYROLL, masked for everyone else
| Year | Gross income | Federal tax |         (table → atomic chunk, v1.22 Part X)
```



**Roles:**

| Role                 | Class entitlements    |
| -------------------- | --------------------- |
| `bob` (default user) | `PUBLIC` only         |
| `hr` (gid 4000)      | `PUBLIC`, `PERSONAL`  |
| `payroll` (gid 4100) | `PUBLIC`, `FINANCIAL` |

`hr` and `payroll` are deliberately disjoint so least privilege is visible: each can find the *other's* classified section (masked), but only ever sees the *original* value within their own.

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
      RESTRICTED:
        action: MASK
        store_original: false
      PERSONAL:
        action: MASK
        store_original: true
      FINANCIAL:
        action: MASK
        store_original: true
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

| Step | Action                                        | Expected                                                                                          | Marker                                                        |
| ---- | ---------------------------------------------- | ----------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| 1    | Ingest `employee-jordan.md`                   | `manifest.state = INDEXED`; chunks have `classification[]`                                        | `[WORKS]`                                                      |
| 2    | Inspect chunks                                | `section_path: ["Employee File - Jordan Reyes", "Contact"]`; table chunk with `structured_content`; Contact and Compensation chunks each show `content_masked` + `content_original`; the identity chunk shows `content_masked` only | `[WORKS]`                                                       |
| 3    | Search as `bob` for `"000-00-0000"`           | **Zero hits** (SSN is `RESTRICTED`, `store_original: false` — literal never stored, for any role)  | `[BLOCKED: detector + masking, store_original:false]`          |
| 4    | Search as `hr` for `"Springfield"`            | Returns Contact section with the **original** address (`hr` holds `PERSONAL`)                     | `[BLOCKED: class_grants + representation selection]`           |
| 5    | Search as `payroll` for `"Springfield"`       | **Zero hits** — the literal `"Springfield"` was masked out of `content_masked`, and `payroll` lacks `PERSONAL` so only that field is searched | `[BLOCKED: representation selection]`                          |
| 5b   | Search as `payroll` for `"Contact"`           | Returns the Contact chunk with `"Home address: [REDACTED:PERSONAL] phone: [REDACTED:PERSONAL]"` — chunk **is** reachable, value is masked, not excluded | `[WORKS: masked representation still searchable]`              |
| 6    | Search as `payroll` for `"gross income"`      | Returns Compensation table with the **original** figures (`payroll` holds `FINANCIAL`)            | `[BLOCKED: class_grants + representation selection]`           |
| 7    | Search as `hr` for `"gross income"`           | Returns the same Compensation chunk with `"Gross income: [REDACTED:FINANCIAL]"` — chunk **is** reachable, value is masked, not excluded | `[WORKS: masked representation still searchable]`              |
| 7b   | Search as `bob` for `"executive compensation program"` | Returns the eligibility sentence **unmasked** for every role — masking made no change to this chunk, so it has a Single representation | `[WORKS: Single representation, no dual gate needed]`          |
| 8    | Revoke `payroll` mid‑session                  | `payroll`'s subsequent searches for `"gross income"` resolve to `content_masked`, within §11 propagation window (p99 < 300ms) | `[BLOCKED: class propagation over outbox]`                     |
| 9    | Inspect Cassandra `chunks`                    | Identity chunk: `"SSN: [REDACTED:SSN]"`, no `content_original` field exists. Compensation chunk: `content_masked` shows `"Gross income: [REDACTED:FINANCIAL]"`, `content_original` shows the real figures | `[BLOCKED: store_original:false for SSN; class-grant-gated for FINANCIAL]` |
| 10   | Inspect Kafka `synflux_enriched_chunks`       | Same masked + (where applicable) original payload shape as Cassandra                              | `[BLOCKED: masking-outcome decided before publish]`             |
| 11   | Inspect `synquest` term dictionary            | `"000-00-0000"` absent from inverted index entirely; `"gross income"` term present, pointing at both the `content_masked` and `content_original` Lucene fields | `[BLOCKED: store_original:false for SSN; dual field for FINANCIAL]` |

## 6. Negative‑Test Commands (Acceptance Assertions)

Run these after ingestion. Assertions 1–5 verify the SSN literal (`RESTRICTED`, `store_original: false`) is never stored anywhere, for any role. Assertions 6–7 verify the FINANCIAL/PERSONAL dual-representation gate: the masked value is universally searchable, the original value is retrievable only with the matching class grant:

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

# 6. Compensation chunk: original figures reachable only by payroll
curl -s -H "X-Synanton-Role: payroll" http://localhost:8083/search?q=gross+income \
  | grep -q "REDACTED:FINANCIAL" && echo "FAIL: payroll got masked value" || echo "PASS"

# 7. Compensation chunk: hr gets the masked value, not the original, not zero hits
curl -s -H "X-Synanton-Role: hr" http://localhost:8083/search?q=gross+income \
  | jq -e '.hits | length > 0' > /dev/null \
  && curl -s -H "X-Synanton-Role: hr" http://localhost:8083/search?q=gross+income | grep -q "REDACTED:FINANCIAL" \
  && echo "PASS" || echo "FAIL: hr did not get masked hit"
```



All seven must print `PASS`.

## 7. What This Demo Proves / Does Not Prove Yet

| Claim                                                                  | Proved by                                | Mapped to    |
| ------------------------------------------------------------------------ | ------------------------------------------- | ------------ |
| Chunks carry `section_path`, `page_start`, table `structured_content`   | Step 2                                    | v1.22 Part X |
| SSN literal (`store_original: false`) absent from all stores, all roles  | Steps 3, 9–11, negative tests 1–5         | SEC‑2, SEC‑4 |
| `hr` gets the original PERSONAL value, `payroll` gets it masked          | Steps 4, 5b, 9                            | SEC‑1, SEC‑3 |
| `payroll` gets the original FINANCIAL value, `hr` gets it masked         | Steps 6, 7, 9, negative tests 6–7         | SEC‑1, SEC‑3 |
| Masked chunks remain fully searchable — not excluded                    | Steps 5b, 7                               | SEC‑3        |
| Unmodified content is identical for every role (Single representation) | Step 7b                                   | §3.2a        |
| Revocation propagates within SLO                                        | Step 8                                    | SEC‑1 (§11)  |
| Representation selection is compile‑time, not post‑filter               | Steps 4–7 (term statistics not leaked)   | SEC‑3        |

**Not yet proved (future work):**

- Physical separation per class (SEC‑6) — optional, regulated tenants only
- LLM‑based classification (A4) — not recommended as sole gate
- Pre‑ingest sanitisation of original PDF — out of scope (see §5 of [synanton-design-1.23.md](../architecture/synanton-design-1.23.md))

## 8. Troubleshooting

| Symptom                                      | Likely cause                                        | Fix                                                                    |
| ---------------------------------------------- | ------------------------------------------------------ | --------------------------------------------------------------------- |
| `bob` sees SSN in results                    | Detector not enabled or masking not applied         | Check `synflux.classification.enabled=true`                            |
| `hr` sees zero hits (instead of masked) for FINANCIAL | Representation selection not wired — falling back to old exclusion behaviour | Check `AclInjector.injectRepresentationClauses` resolves `masked`, not an empty result |
| `payroll` sees `[REDACTED:FINANCIAL]` instead of the original | `class_grants` not seeded, or `content_original` field missing | Run `./scripts/seed-class-grants.sh`; verify `SpanMasker` produced a dual outcome |
| Revocation not immediate                     | Outbox lag                                          | Check `topology_outbox` table; restart dispatcher                      |
| Negative test fails on SSN                   | Masking not applied before Cassandra, or `store_original` defaulted to `true` for `RESTRICTED` | Check `synflux.classification.policy.RESTRICTED.action=MASK` and `.store-original=false` |
