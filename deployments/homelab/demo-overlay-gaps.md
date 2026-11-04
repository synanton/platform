# Demo overlay — gap register

Checkpoint-1 dry run of the `local-real-demo` overlay (EWP-2 outbox slice) on the
4-node homelab cluster. Records what blocks the demo path and what belongs to
track B (rebuild/reimage cycle) vs track A (overlay/config fixes already landed).

## Status

| Phase | State |
|---|---|
| Phase 1 — cluster pre-flight | done (`verify-cluster.sh`, PR #498) |
| Phase 2 — data layer + overlay up | done (all pods Running except `platform`) |
| Phase 3 — mint token + search 200 | **blocked** (JWT signature mismatch) |
| Phase 4 — graph neighbors / ontology counts | not reached |
| Phase 5 — UI routes | not reached |
| Phase 6 — browser check | not reached |

## Blocker — JWT signature mismatch (track B)

`security` mints a tenant token via `POST /v1/auth/tenant` (body
`{"principal_id":"demo-user","tenant_id":"demo-v2"}`), but `synquest` rejects it
with `401 {"code":"AUTHENTICATION_REQUIRED","detail":"Invalid JWT signature"}`.

**Evidence (verified, not assumed):**

- Secret is **not stale**. The 64-char value is byte-identical in all four places:
  `config/.env` (`SYNANTON_JWT_SECRET`), the `synanton-jwt` secret
  (`kubectl -n synanton get secret synanton-jwt -o jsonpath='{.data.secret}'`),
  `security` pod env, and `synquest` pod env. All read `RZPZ…2Nsf`
  (sha256 `6d0280f1…`).
- Token signature verifies against **none** of: that secret, the
  `application.yml` default `change-me-in-production-32chars!!`, the literal
  placeholder string `${SYNANTON_JWT_SECRET:…}`, an empty secret, or a 14-value
  candidate set (`demo`, `synanton`, `secret`, etc.). HMAC-SHA256 over
  `header.payload` computed independently in Python.

**The two images do NOT predate a convention unification — the convention is already unified:**

| | Pinned image | Baked secret source |
|---|---|---|
| `security` | `…/security@sha256:fbf0dee1…` (jar Oct 26) | `application.yml`: `security.jwt.secret: ${SYNANTON_JWT_SECRET:change-me-in-production-32chars!!}`, consumed via `@ConfigurationProperties(prefix="security")` → `Jwt.secret` (extracted: `unzip -p /app/app.jar BOOT-INF/classes/application.yml`) |
| `synquest` | `…/synquest@sha256:fe1f792…` | `SynquestSecurityConfig`: `@Value("${SYNANTON_JWT_SECRET}")` → `JwtVerifier` (extracted: `…SynquestSecurityConfig.class` strings contain `${SYNANTON_JWT_SECRET}`) |

- `security`'s signing bytecode matches current `main` source exactly:
  `jwt.sign(new MACSigner(secret.getBytes()))` (no key derivation), and the
  constructor validation string `demo signing secret must be at least 32 characters`
  matches `DemoTenantTokenIssuer.java:39`. `SecurityProperties` is correctly
  registered via `@EnableConfigurationProperties(SecurityProperties.class)`.
- Both modules reference `${SYNANTON_JWT_SECRET}`. The `security` jar contains a
  single `application.yml` (no `application-*.yml` override).

**Diagnostic sequence (all overlay/config fixes ruled out):**

1. **No stale `SECURITY_JWT_SECRET` env** — `env` on the pod shows only
   `SYNANTON_JWT_SECRET`, no `SECURITY_JWT_SECRET`.
2. **No ConfigMap override** — `kubectl get cm -n synanton -o yaml` contains no
   `security.jwt.secret` / `jwt_secret` / `SECURITY_JWT_SECRET` key.
3. **Deployment references the right key** — `security.yaml` sets
   `SYNANTON_JWT_SECRET` via per-key `valueFrom.secretKeyRef` (`synanton-jwt` →
   `secret`), not `envFrom`.
4. **Decisive env test (negative)** — setting `SECURITY_JWT_SECRET` to the same
   value as `SYNANTON_JWT_SECRET` did **not** make the token verify; setting
   `SECURITY_JWT_SECRET` and `SYNANTON_JWT_SECRET` each to a fresh unique value
   also left the token signature unchanged. So `security.jwt.secret` resolves to a
   **fixed value independent of both env vars**.
5. **Property-source override (negative)** — no `application*.yml`/`.properties`
   outside the jar, no profile yml, no config import; `env` has no
   `SPRING_*`/`CONFIG_*`/`PROFILE` var; the only mounted volume is the `htpasswd`
   secret (no config file mount).
6. **Bean-layer duplicate (negative)** — `SecurityProperties` carries only
   `@ConfigurationProperties(prefix="security")` (no `@Component`/`@Configuration`),
   and `AppConfig` has no `@Bean SecurityProperties`; it is registered exactly once
   via `@EnableConfigurationProperties(SecurityProperties.class)`. Not the `#500`
   duplicate-bean pattern.
7. **Definitive synquest-side confirmation** — a token **self-signed with
   `SYNANTON_JWT_SECRET`** was accepted by synquest (`POST /reindex?tenant=demo-v2`
   → `200 {"status":"done"}`), while a garbage token got `401`. So synquest's
   verifier uses the env secret correctly; **only `security`'s issuer is broken**,
   and the search/index path is otherwise functional.

**Root cause (decisive):** the `@ConfigurationProperties(prefix="security")`
binding of `jwt.secret` does **not** resolve the `${SYNANTON_JWT_SECRET:…}`
placeholder to the environment. Decompilation confirms the signing path is
`new DemoTenantTokenIssuer(props.jwt().secret(), …)` →
`MACSigner(secret.getBytes())` with no key derivation, no hardcoded secret, and
`@EnableConfigurationProperties(SecurityProperties.class)` present; the resolved
value matches neither the env var, nor the yaml default
`change-me-in-production-32chars!!`, nor the literal placeholder. `synquest`'s
`@Value("${SYNANTON_JWT_SECRET}")` resolves the same env var correctly, so the two
binding paths (`@ConfigurationProperties` vs `@Value`) diverge.

**Consequence:** a plain rebuild from `main` will **reproduce** the mismatch —
the code + config are already identical to `main`. The fix is a **code change**,
not a reimage.

**Remediation (track B):**
1. Align `security`'s secret resolution to `@Value("${SYNANTON_JWT_SECRET}")`
   (matching `synquest`), replacing the `@ConfigurationProperties` →
   `props.jwt().secret()` path for the demo issuer. (Alternatively, fix the
   `@ConfigurationProperties` placeholder resolution, but `@Value` is the
   lower-risk, already-proven path.)
2. Rebuild + re-mirror + re-pin `security`, re-verify token accepted.

Note: whether the eventual convention is "align security to `@Value`" or
"align synquest to `@ConfigurationProperties`" is a design choice; the two
binding paths are not equivalent and future JWT work should pick one.

**Durable test (must land with the fix):** add a signer↔verifier contract test
that fails if the demo issuer's secret diverges from the verifier's convention —
i.e. `DemoTenantTokenIssuer` signs a token that `SynquestSecurityConfig`'s
`JwtVerifier` (same secret) verifies. This is a one-test contract that would have
caught this bug at build time instead of at demo bring-up. Without it, the same
class of bug recurs.

## Gap — demo services are ClusterIP, not NodePort (decision: cherry-pick 3999065)

The demo overlay manifests on `main` declare `type: ClusterIP` for
`security` (8081), `synquest` (8083), and `ui` (80). External browser access
therefore requires `kubectl port-forward`.

**Decision: cherry-pick `3999065` to `main`** (not branch-deploy, not full merge).

**Verified detail — `3999065` is a *mixed* commit, and the NodePort is UI-only:**

- `3999065` touches 5 files: `synquest.Dockerfile`, `synquest.prebuilt.Dockerfile`,
  `local-real-demo/synquest.yaml` (demo-data env vars + image pin), `ui.yaml`
  (NodePort + image pin), `mirror-images.sh`. It is **not** NodePort-only.
- The NodePort change exists **only in `ui.yaml`**: `type: ClusterIP` →
  `type: NodePort` + `nodePort: 30080`. `security` and `synquest` were **never**
  NodePort in any commit (no `30081`/`30083` in history) — the UI is the single
  external entry point and proxies the v1 routes to `security`/`synquest` over
  ClusterIP.
- The `ui.yaml` NodePort hunk is interleaved with a `ui` image-pin change
  (`604c007…` → `944d293…`) and a `source-commit` annotation bump. A clean
  cherry-pick must extract **only** the `type: NodePort` + `nodePort: 30080` lines,
  not the image pin: `git cherry-pick -n 3999065 && git reset HEAD …` and re-apply
  just those two lines (or hand-write the two-line change as a fresh commit).

**Fork resolved — the currently-pinned `604c007…` image already has the `/v1/`
proxy.** The deployed `ui` pod is `604c007…`, and its baked nginx config proxies
`/v1/auth/ → security:8081`, `/v1/search → synquest:8083`, `/v1/knowledge/ →
synquest:8083`, `/v1/sources|ingestion-runs|operations/ → synflux:8090`. There is
**no** `ui-nginx-conf` ConfigMap (proxy is in the image, not the ConfigMap). So
the cherry-pick is **NodePort-lines-only** — the `604c007…→944d293…` pin bump is
**not** required; `604c007…` serves the proxy correctly. (The `944d293…` bump was
for demo-data baking, unrelated to the proxy.)

**Post-cherry-pick evidence to record** (so the next operator reads the port,
not re-derives it): `kubectl get svc -n synanton ui -o jsonpath='{.spec.ports[0].nodePort}'`
— confirm `30080`, and note `security`/`synquest` stay ClusterIP (proxied by UI).

## Gap — htpasswd backend loads zero users (decision: config/.env)

`security` logs `Loaded 0 users from /etc/synanton/users`. The `security-htpasswd`
secret is empty. Not blocking demo token mint (that endpoint bypasses htpasswd),
but blocks the username/password login path.

**Decision: source from `config/.env`** (`DEMO_USER`/`DEMO_PASSWORD`), matching
the existing `postgres-cred`/`minio-cred`/`synanton-jwt` pattern.

**Verified detail — the file format is custom, not standard htpasswd, and the
hash is bcrypt (not apr1):**

`HtpasswdBackend` reads `/etc/synanton/users` (secret key `users`, mounted at
`/etc/synanton`), one user per line, **4 colon-separated fields**:

```
username:bcrypt_hash:uid:gid1,gid2,...
```

- `username`, `bcrypt_hash` (at.favre.lib:bcrypt — `$2a$`/`$2y$`), `uid` (int),
  `gids` (comma-separated ints). Lines are otherwise skipped with a warning.
- `openssl passwd -apr1` is **wrong** (md5, and 2 fields). Use Apache
  `htpasswd -nbB "$DEMO_USER" "$DEMO_PASSWORD"` (bcrypt) and append `:uid:gid`, e.g.
  `demo:$2y$…:1:1`.
- The backend `reload()`s **once, in the constructor** — no file-watcher, no
  scheduled reload. A secret change requires
  `kubectl rollout restart deploy/security -n synanton` **after** applying the
  secret (order matters — restart-before-apply loads the old empty secret again).

**Verified on the operator host:** `/usr/bin/htpasswd` is present and
`htpasswd -nbB` emits `$2y$` (at.favre.lib bcrypt accepts `$2a$`/`$2b$`/`$2y$`).
`HtpasswdBackend` does **no** OS-level uid/gid validation (no
`getpwnam`/`getgrnam`/`getpwuid`) — `uid`/`gids` are opaque ints, so `1:1` is safe.

`deploy-demo.sh` must therefore: read `DEMO_USER`/`DEMO_PASSWORD` from
`config/.env` (error out if unset), bcrypt-hash the password, write
`<user>:<hash>:1:1` to a temp file, `kubectl create secret generic
security-htpasswd --from-file=users=…`, then restart `security`. `config/.env` is
gitignored (verified); `config/.env.example` is committed with placeholder values.

## Gap — `curl` absent from Chainguard synquest image

`kubectl exec -n synanton deploy/synquest -- curl …` fails with
`exec: "curl": executable file not found`. In-cluster smoke tests of
`synquest`/`security` must use `wget` or run from a sidecar/host.

## Gap — platform pod CrashLoopBackOff (#500)

`platform` is CrashLoopBackOff with `BeanDefinitionOverrideException` on
`gpuEmbeddingAdapter` (duplicate bean). Pre-existing, deferred; out of
checkpoint-1 path. Tracked in issue #500. Verified it does not block the demo
path: `synquest`/`security`/`ui`/`synflux` are all `Running`, and search is
reached independently of `platform`.

## Completed this session

| Finding | Status |
|---|---|
| `verify-cluster.sh` check 5 false positive (cordoned control-plane) | Fixed (#498) |
| `minio.yaml` `mc` prefix bug | Fixed (#499) |
| `synflux.yaml` `CASSANDRA_*` env missing | Fixed (#499) |
| `synflux.yaml` fixture root / emptyDir missing | Fixed (#499) |
| `deploy.sh` deployed wrong slice | Fixed (`deploy-demo.sh`, #499) |
| `platform` `gpuEmbeddingAdapter` duplicate bean | Filed (#500) |
| JWT image mismatch | Diagnosed — `@ConfigurationProperties` placeholder doesn't resolve env (code fix, track B) |
| NodePort absence on `main` | Documented — scope decision needed |
| `security-htpasswd` empty | Documented — source decision needed |
| `curl` absent from Chainguard image | Documented — port-forward from operator |
| `/v1/auth/tenant` body shape | Documented — `{principal_id, tenant_id}`, not `{tenant}` |
| Reindex port 8083 + auth | Documented |

## Notes for the next run

- Mint token: `kubectl port-forward -n synanton deploy/security 18081:8081`, then
  `curl -s -X POST localhost:18081/v1/auth/tenant -H 'Content-Type: application/json' -d '{"principal_id":"demo-user","tenant_id":"demo-v2"}'`.
- Search: `kubectl port-forward -n synanton deploy/synquest 18083:8083`, then
  `curl -s 'localhost:18083/v1/search?tenant=demo-v2&q=…' -H "Authorization: Bearer <token>"`.
- Reindex: `curl -s -X POST 'localhost:18083/reindex?tenant=demo-v2' -H "Authorization: Bearer <token>"`.
