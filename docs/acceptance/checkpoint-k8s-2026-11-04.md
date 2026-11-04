# K8s demo checkpoint — merged acceptance (2026-11-04)

## Cluster
- `verify-cluster.sh`: all 5 PASS (check 5 false positive fixed in #498).
- Nodes: node0 control-plane cordoned; node1–3 Ready.
- GPU taints: untouched (upgrade-required / pod-restart-required hold).

## Deployed images
| Service | Digest |
|---|---|
| security | `sha256:cf2566ac…` (JWT `@Value` fix) |
| synquest | `sha256:ffd40f16…` (baked demo corpus) |
| ui | `sha256:604c0079…` (unchanged; `/v1/` proxy already baked) |
| platform | CrashLoopBackOff (#500, pre-existing, out of demo path) |

## Fixes landed
- #501 — `ui` NodePort 30080 (cherry-picked from 3999065, NodePort lines only).
- #502 — `security-htpasswd` generation from `config/.env` (bcrypt, 4-field).
- #503 — security `@Value("${SYNANTON_JWT_SECRET}")` + signer/verifier contract test.
- #504 — pin security image to `cf2566ac`.
- #505 — pin synquest to demo-data image `ffd40f16` + data env vars.
- demo fixes 5/6 + tenant rename (on `demo/vertical-slice` → PR #469).

## Verification — PASS
- `/v1/auth/tenant` (body `{principal_id, tenant_id}`) → 200, token minted.
- `POST /reindex?tenant=demo` (Bearer) → `200 {"status":"done"}`.
- Legacy `/search` (tenant `demo`) → 200, 20 hits.
- Ontology `/v1/demo/ontology/all?df_band=all` → 273; `df_band=graph_useful` → 31.
- UI `/` → 200, `/ontology` → 200 (NodePort `192.168.10.31:30080`).
- `security` log: `Loaded 1 users from /etc/synanton/users`.

## Verification — BLOCKED (data-track)
- `POST /v1/demo/search` → 503 (canonical Cassandra store empty).
- `GET /v1/demo/graph/neighbors/{chunk}` → 404 (chunks not in canonical store).
- Corpus loaded into legacy Lucene index (`doc_count: 18895`), not the canonical store.

## Tenant naming
- Deployed tenant: **`demo`** (seed via `SYNQUEST_SEED_DEMO_TENANT=demo`).
- Runbook/scripts previously referenced `demo-v2`; corrected to `demo` in
  `browser-demo.md`, `run-query.py`, `start-browser-demo.sh`, `browser-smoke.py`,
  `expired-token.py`.

## Known issues (follow-ups)
- #506 — synquest RWO PVC + RollingUpdate deadlocks on image change (`strategy: Recreate`).
- #507 — canonical Cassandra store empty (`/v1/demo/search` 503, graph 404).
- #508 — corpus mismatch (18895 docs vs 634-chunk eval corpus).
- #500 — platform `gpuEmbeddingAdapter` duplicate bean (pre-existing, out of path).

## Deferred (B scope)
- Graph UI panel.
- Ontology click-to-filter.
- Canonical-store ingestion (synflux fixtures).

## Reproducible from
- git: `main` @ merge of #501–#505 (security/synquest pins), demo fixes on PR #469.
- `config/.env`: `DEMO_USER`, `DEMO_PASSWORD` set (gitignored).
- commands: `deploy-demo.sh` + `kubectl apply -k k8s/local-real-demo/`.
