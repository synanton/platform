# Synanton Demo - Implementation Plan

**Scope:** stand up a runnable `docker compose` demo of the Synanton platform that exercises three things end‑to‑end:

1. **Security** - authentication and authorization driven by **local OS users and POSIX file permissions** over a mounted ontology directory.
2. **Syntology** - ontology service (REST API, Jena TDB2 storage, SHACL validation, versioning).
3. **Syntology Admin UI** - React SPA with Cytoscape‑based graph visualisation and editing.

Everything else from the platform (synflux, synquest, relix, planner, gateway, synapt, control‑plane, Kafka, Redis, Cassandra, Neo4j, GraphDB, vLLM, Temporal, Synton agent, MCP) is **out of scope for the demo** but reserved as empty directories so the monorepo shape does not churn later.

This document is the source of truth for what to build. It is divided into:

- §1 - Monorepo source folder layout
- §2 - Minimal module scope
- §3 - Component design per module
- §4 - Docker Compose topology
- §5 - Demo user journey
- §6 - Implementation order and deliverables

---

## 1. Monorepo source folder layout

Aligned with `Synanton Monorepo Design Document.md`. Directories not used by the demo are kept empty so future modules drop in without restructuring.

```
synanton/
├── README.md
├── .gitignore
├── .env.example
├── docs/                                   # design docs (move from doc/)
│   ├── architecture/
│   └── syntology/
├── gradle/
│   ├── libs.versions.toml                  # central Java dep versions
│   └── wrapper/
├── build.gradle.kts                        # root build script
├── settings.gradle.kts                     # includes only active modules
├── gradle.properties
├── scripts/
│   ├── setup-dev.sh
│   ├── build-all.sh
│   └── run-demo.sh                         # wrapper around docker compose
│
├── java/
│   ├── shared/
│   │   ├── common/                         # error model, JWT util, tenant context
│   │   └── protobuf/                       # placeholder (no gRPC in demo)
│   ├── security/                           # AuthN/Z + local-FS IdP
│   ├── topology/                           # org/user/ACL/policy store
│   ├── syntology/                          # ontology service
│   │
│   ├── synvault/                           # placeholders - build.gradle.kts only,
│   ├── synflux/                            #   excluded from settings.gradle.kts
│   ├── synflux-router/                     #   until they have real source
│   ├── relix/
│   ├── planner/
│   ├── gateway/
│   ├── synapt/
│   └── control-plane/
│
├── rust/                                   # placeholder
│   └── Cargo.toml                          # workspace manifest, no members
│
├── ui/
│   ├── package.json                        # pnpm workspace root
│   ├── pnpm-workspace.yaml
│   ├── tsconfig.base.json
│   ├── shared-ui/                          # minimal: auth hook, API client base
│   └── syntology-admin/                    # the demo SPA
│
├── deployment/
│   ├── docker/
│   │   ├── compose.yaml                    # demo entry point
│   │   ├── postgres/init.sql               # topology + syntology schemas
│   │   ├── security.Dockerfile
│   │   ├── topology.Dockerfile
│   │   ├── syntology.Dockerfile
│   │   └── syntology-admin.Dockerfile      # nginx serving built SPA
│   ├── full/                               # K8s manifests (placeholder)
│   ├── standalone/                         # systemd/Ansible (placeholder)
│   └── embedded/                           # placeholder
│
├── test/
│   ├── e2e/                                # Playwright against compose stack
│   └── fixtures/
│       └── ontologies/                     # sample Turtle/RDF
│
├── tools/                                  # codegen, lint configs (empty)
└── demo-data/
    └── ontologies/                         # bind-mounted into containers
```

### 1.1 Conventions

- Java module package roots: `org.synanton.<module-id>`. Syntology uses `org.synanton.synt.*` per the manifesto.
- UI packages scoped as `@synanton/<module-id>`.
- Configuration keys, metrics, log fields prefixed with the module id.
- `settings.gradle.kts` includes only `java:shared:common`, `java:security`, `java:topology`, `java:syntology` at the start.

---

## 2. Minimal module scope

| Module | In demo | What is cut |
|---|---|---|
| **security** | Local‑FS `IdentityProviderPort` (PAM or htpasswd inside the container), JWT issuance, POSIX permission helper. | Outbound auth broker, RFC 8693, MCP revalidation, IdP amortization, topology outbox dispatch. |
| **topology** | One Postgres schema with `organizations`, `users` (mapped from OS uids), `acl_grants` (seeded from filesystem). Read mutation API only. | Outbox dispatcher, Neo4j projection, residency/budget/regulatory policies. |
| **syntology** | REST API (admin endpoints from §5.1 of the syntology manifesto), Postgres metadata tables, Jena TDB2 OntologyAdapter, in‑process Caffeine cache, capability endpoint, SHACL validation (Jena native). | MCP server, gRPC server, Kafka producer, Redis cache, OWL reasoning, GraphDB / RDF4J adapters, session‑pin TTL worker. |
| **syntology-admin UI** | Login, OntologyList (versions, upload Turtle/RDF), OntologyEditor (Cytoscape graph, details panel, inline create/edit), SHACL validation panel, version bump. JWT in `Authorization` header. | Synton chat panel, MCP/SSE client, audit log viewer, session‑pin UI, real‑time WebSocket. |
| **shared/common** | Tenant‑context filter, problem‑detail error model, JWT verification util, DTOs reused across security/topology/syntology REST. | protobuf (not needed without gRPC). |

### 2.1 Infra services in `docker compose`

| Service | Image | Purpose |
|---|---|---|
| `postgres` | `postgres:16` | Hosts both `topology` and `syntology` logical schemas. |
| `security` | locally built JAR | Issues JWTs from local creds, exposes IdP REST endpoints. |
| `topology` | locally built JAR | Persists org / users / ACL grants. |
| `syntology` | locally built JAR | REST API + Jena TDB2 storage on a named volume. |
| `syntology-admin` | nginx + built SPA | Serves SPA, proxies `/api/*` to syntology and `/auth/*` to security. |

Bind mount: `./demo-data/ontologies → /data/ontologies` shared by `security` and `syntology` containers. This is the **local filesystem** whose POSIX bits drive authorization.

No Kafka, no Redis, no Cassandra, no Neo4j, no GraphDB, no vLLM, no Temporal, no Synton.

---

## 3. Component design per module

### 3.1 `java/shared/common`

**Purpose:** thin library reused by `security`, `topology`, `syntology`. No Spring Boot - plain Java + Jackson + Nimbus JOSE.

Contents:

- `org.synanton.common.error.ProblemDetail` - RFC 7807 problem detail record.
- `org.synanton.common.error.SynantonException` + subclasses (`AuthException`, `ForbiddenException`, `NotFoundException`, `ValidationException`).
- `org.synanton.common.tenant.TenantContext` - thread‑local `(tenantId, subject, uid, gid[])`.
- `org.synanton.common.tenant.TenantContextFilter` - Spring filter (used downstream) that reads `X-Tenant-ID` header and the JWT and populates `TenantContext`.
- `org.synanton.common.jwt.JwtVerifier` - verifies HS256 JWTs using a shared secret read from env `SYNANTON_JWT_SECRET`.
- `org.synanton.common.jwt.SubjectAssertion` - record `(subject, uid, gid[], tenantId, exp)`.

Build target: `synanton-common-0.1.0.jar`, published to local Maven cache via `publishToMavenLocal`.

### 3.2 `java/security`

**Stack:** Java 21, Spring Boot 3.3, no DB. Stateless. Port 8081.

**Endpoints:**

| Method | Path | Body | Returns |
|---|---|---|---|
| `POST` | `/auth/login` | `{ "username": "...", "password": "..." }` | `{ "token": "<jwt>", "expires_in": 3600 }` |
| `POST` | `/auth/validate` | `{ "token": "..." }` | `{ "valid": true, "subject": "...", "uid": 1001, "gid": [1001, 100] }` |
| `GET`  | `/capabilities` | - | Module capabilities matrix (Section 33 format). |

**Implementation notes:**

- Identity backend selected by `security.idp.backend = htpasswd | pam`. Default `htpasswd` for portability.
  - `htpasswd` backend reads `/etc/synanton/users` (mounted) containing `username:bcrypt(password):uid:gid1,gid2`.
  - `pam` backend (optional) shells out to `/etc/shadow` via JNI (`libpam`). Not required for demo.
- JWT signed HS256 with `SYNANTON_JWT_SECRET`. Claims: `sub`, `uid`, `gid`, `tenant_id` (always `demo`), `iat`, `exp` (1 h).
- Exposes a small library JAR `security-fs-perm` consumed by `syntology` that wraps `java.nio.file.Files.getPosixFilePermissions` and checks `(uid, gids)` against file mode bits + ACLs.

**Configuration (env vars):**

- `SYNANTON_JWT_SECRET` - required, ≥ 32 bytes.
- `SECURITY_IDP_BACKEND` - `htpasswd` (default).
- `SECURITY_HTPASSWD_PATH` - `/etc/synanton/users`.

### 3.3 `java/topology`

**Stack:** Java 21, Spring Boot 3.3, Spring JDBC, Flyway. Port 8082.

**Postgres schema** (`topology` schema in the shared DB; init via Flyway migration `V1__init.sql`):

```sql
CREATE TABLE topology.organizations (
  org_id     UUID PRIMARY KEY,
  name       TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE topology.users (
  user_id    UUID PRIMARY KEY,
  org_id     UUID NOT NULL REFERENCES topology.organizations(org_id),
  username   TEXT NOT NULL UNIQUE,
  uid        INT  NOT NULL,
  gids       INT[] NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE topology.acl_grants (
  grant_id      UUID PRIMARY KEY,
  org_id        UUID NOT NULL,
  subject_id    UUID NOT NULL,
  subject_type  TEXT NOT NULL CHECK (subject_type IN ('USER','GROUP')),
  resource_path TEXT NOT NULL,   -- absolute path under /data/ontologies
  permission    TEXT NOT NULL CHECK (permission IN ('READ','WRITE','ADMIN')),
  source        TEXT NOT NULL,   -- 'FS_BOOTSTRAP' | 'MANUAL'
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**Endpoints:**

| Method | Path | Purpose |
|---|---|---|
| `GET`  | `/topology/users` | List users known to the demo tenant. |
| `GET`  | `/topology/users/{uid}/grants` | List grants visible to a user. |
| `POST` | `/topology/acl/reseed` | Re‑scan the ontology directory and rebuild grants. |
| `GET`  | `/capabilities` | Module capabilities. |

**Bootstrap behaviour:**

On startup, a `FilesystemAclSeeder` walks `/data/ontologies`, reads POSIX mode + owner/group for each file/directory, and inserts matching `acl_grants` rows. This makes the topology DB a queryable mirror of the filesystem at boot - the UI shows the grants, but the **authoritative** check on each request still goes through `security`'s permission helper against the live file mode.

### 3.4 `java/syntology`

**Stack:** Java 21, Spring Boot 3.3, Apache Jena 5.x (TDB2 + SHACL), Caffeine. Port 8083.

**Package layout (hexagonal):**

```
org.synanton.synt
├── api/                # REST controllers, request/response DTOs
├── domain/             # OntologyService, EntityType, RelationType, OntologyVersion
│   └── port/
│       ├── in/         # OntologyService (inbound port)
│       └── out/        # OntologyAdapter, MetadataRepository, EventPublisher
├── infra/
│   ├── jena/           # JenaTdb2OntologyAdapter implements OntologyAdapter
│   ├── jdbc/           # Postgres metadata repo
│   ├── cache/          # Caffeine-based entity cache
│   ├── events/         # NoopEventPublisher (Kafka stub)
│   └── security/       # FsPermissionGuard - delegates to security-fs-perm helper
└── app/                # Spring Boot config, capability descriptor
```

**REST endpoints (subset of §5.1 of the syntology manifesto):**

| Method | Path | Purpose |
|---|---|---|
| `GET`  | `/api/v1/ontology/versions` | List versions. |
| `POST` | `/api/v1/ontology/versions` | Bump version. |
| `POST` | `/api/v1/ontology/schema` | Upload Turtle / RDF / OWL file. |
| `GET`  | `/api/v1/ontology/entities?label=&version=` | Resolve / list entities. |
| `POST` | `/api/v1/ontology/entities` | Create entity type. |
| `GET`  | `/api/v1/ontology/relations?label=&version=` | Resolve / list relations. |
| `POST` | `/api/v1/ontology/relations` | Create relation type. |
| `GET`  | `/api/v1/ontology/graph?version=` | Return graph as Cytoscape JSON `{ nodes, edges }`. |
| `POST` | `/api/v1/ontology/validate` | Run SHACL validation on a candidate concept. |
| `GET`  | `/api/v1/ontology/capabilities` | Publish capability matrix. |

**Postgres schema** (`syntology` schema, Flyway `V1__init.sql`): the four tables defined in §6.1 of the syntology manifesto - `syntology_ontologies`, `syntology_entity_types`, `syntology_relation_types`, `syntology_session_pins`.

**Storage:** Jena TDB2 dataset rooted at `/var/lib/syntology/tdb2`, one named graph per `(tenant_id, version)`. The graph file IRI is mirrored on disk under `/data/ontologies/<tenant>/<version>.ttl` so POSIX permissions remain the source of truth for access.

**Authorization on every mutating call:**

1. Verify JWT via `shared/common` `JwtVerifier`.
2. Resolve the target file path from `(tenantId, version)`.
3. Call `FsPermissionGuard.checkWrite(path, uid, gids)`. On failure → `403 ProblemDetail`.
4. Apply mutation in Jena and write metadata row in Postgres in a single transactional block (Jena uses its own txn; Postgres commits last - best‑effort consistency, acceptable for demo).

**Capability matrix** published:

```json
{
  "module_id": "syntology",
  "module_version": "0.1.0",
  "features": {
    "SHACL_VALIDATION": "NATIVE",
    "OWL_REASONING": "EMULATED",
    "DYNAMIC_TENANT_REPOS": "FALLBACK"
  }
}
```

### 3.5 `ui/syntology-admin`

**Stack:** React 18, TypeScript 5, Vite, Tailwind CSS + Radix UI, Redux Toolkit, TanStack Query, Cytoscape.js, React Hook Form, axios. Served behind nginx in the container.

**Routes:**

| Path | Component | Purpose |
|---|---|---|
| `/login` | `LoginPage` | Username/password → POST `/auth/login` → store JWT in memory + httpOnly cookie. |
| `/` | `Dashboard` | Recent activity, capability matrix, current user info (uid/gid). |
| `/ontologies` | `OntologyList` | Versions, status, upload Turtle/RDF. |
| `/ontologies/:version` | `OntologyEditor` | Cytoscape graph, details panel, toolbar, search bar. |
| `/grants` | `GrantsView` | Read‑only view of `topology.acl_grants` for the logged‑in user. |
| `/settings` | `Settings` | JWT info, logout. |

**Component tree** (matches §6 of the UI manifesto, but pruned):

```
src/
├── app/                  # routing, theme, auth guard
├── pages/
│   ├── Login/
│   ├── Dashboard/
│   ├── OntologyList/
│   ├── OntologyEditor/
│   │   ├── GraphCanvas/     # Cytoscape wrapper
│   │   ├── DetailsPanel/
│   │   ├── Toolbar/
│   │   └── SearchBar/
│   ├── GrantsView/
│   └── Settings/
├── components/
│   ├── Layout/
│   ├── Forms/
│   ├── Modals/
│   └── Table/
├── services/
│   ├── authApi.ts        # security endpoints
│   ├── ontologyApi.ts    # syntology endpoints
│   └── topologyApi.ts    # topology endpoints
├── hooks/                # useOntology, useAuth, useGraph
├── store/                # auth, ui slices
├── types/                # generated from OpenAPI (or hand-written)
└── utils/                # cytoscape layout, RDF serialisation helpers
```

**Cytoscape configuration:** CoLa layout default, Dagre fallback for taxonomy view; node colour by type (Class / Property / Datatype); edge style differentiates object vs data properties.

**Auth flow:** Axios interceptor injects `Authorization: Bearer <jwt>`. On `401` the SPA redirects to `/login`. A small auth context keeps the decoded JWT (for displaying uid/gid in the header).

---

## 4. Docker Compose topology

### 4.1 `deployment/docker/compose.yaml` - service map

```
                ┌──────────────────────────┐
   Browser ───► │  syntology-admin (nginx) │  :8080
                │  /api/v1/* → syntology   │
                │  /auth/*   → security    │
                │  /topology/* → topology  │
                └────────────┬─────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        ▼                    ▼                    ▼
 ┌────────────┐        ┌────────────┐       ┌────────────┐
 │  security  │        │  topology  │       │  syntology │
 │   :8081    │        │   :8082    │       │   :8083    │
 └──────┬─────┘        └──────┬─────┘       └─────┬──────┘
        │                     │                   │
        ▼                     ▼                   ▼
 ┌──────────────┐     ┌──────────────┐     ┌────────────────┐
 │ /etc/synanton│     │  postgres:16 │     │ Jena TDB2 vol  │
 │   /users     │     │   :5432      │     │ /var/lib/synt  │
 └──────────────┘     └──────────────┘     └────────────────┘

        Shared bind mount  ./demo-data/ontologies  →  /data/ontologies
        (mounted into security, topology, and syntology)
```

### 4.2 Volumes

- `postgres-data` - named volume for Postgres.
- `syntology-tdb2` - named volume for Jena TDB2 dataset.
- `./demo-data/ontologies:/data/ontologies` - bind mount; POSIX bits are authoritative.
- `./demo-data/users:/etc/synanton/users:ro` - htpasswd file consumed by `security`.

### 4.3 Networking

Single user‑defined bridge network `synanton-demo`. Only `syntology-admin` exposes a host port (`8080`); all other services are internal. Service discovery via container name.

### 4.4 Secrets and env

- `SYNANTON_JWT_SECRET` set in `.env` (loaded by compose), shared by `security`, `topology`, `syntology`.
- `POSTGRES_PASSWORD` set in `.env`.

### 4.5 Bootstrap order

1. `postgres` starts and runs `init.sql` (creates `topology` and `syntology` schemas, grants).
2. `topology` boots, runs Flyway migrations, runs `FilesystemAclSeeder` against `/data/ontologies`.
3. `syntology` boots, runs Flyway migrations, opens Jena TDB2 dataset, loads sample Turtle from `test/fixtures/ontologies/` if metadata table is empty.
4. `security` boots, loads `htpasswd` file.
5. `syntology-admin` (nginx) starts last.

A small `healthcheck` on each service ensures `depends_on: condition: service_healthy` works.

---

## 5. Demo user journey

Seeded users (defined in `demo-data/users` and matched by uid/gid):

| Username | uid | gids | FS permissions on `/data/ontologies/demo` |
|---|---|---|---|
| `alice` | 1001 | `1001, 2000` (group `editors`) | `rwx` (owner) |
| `bob`   | 1002 | `1002, 3000` (group `viewers`) | `r-x` via group `viewers` |
| `admin` | 1000 | `1000, 2000, 3000` | `rwx` (group `editors`) |

1. `docker compose up -d --build` → wait for healthchecks → open `http://localhost:8080`.
2. Login as `alice` - JWT issued with `uid=1001`, `gids=[1001,2000]`.
3. UI calls `GET /api/v1/ontology/versions` → syntology checks read access via FS → returns versions.
4. Open ontology in editor; Cytoscape renders graph from `GET /api/v1/ontology/graph`.
5. Add a new class `Product` via toolbar → POST to syntology → permission check passes (alice is owner) → Jena commits → Postgres metadata row inserted → UI refetches via TanStack Query.
6. Run SHACL validation → response highlights any violations in the graph.
7. Bump version → new named graph in Jena, new row in `syntology_ontologies`, old version marked `DEPRECATED`.
8. Logout, login as `bob` (read‑only group). Same actions in step 5 now return `403 ProblemDetail` (`code=ERR_FS_PERMISSION_DENIED`). UI surfaces a toast.
9. `/grants` page shows the seeded ACL rows visible to the current user - visibly mirrors the filesystem permissions.

This single journey exercises:

- **Security:** JWT issuance from local credentials, POSIX permission check on every mutation.
- **Syntology:** REST API, Jena TDB2 persistence, SHACL validation, version bump, capability matrix.
- **UI:** login, graph visualisation, inline editing, validation, error handling, role‑aware display.

---

## 6. Implementation order and deliverables

The order is chosen so each step produces a runnable artifact that the next step depends on. Every step ends with `./gradlew build` + `docker compose up` succeeding (the compose stack grows as services come online).

### Step 0 - Repo scaffolding ✅ Done

- Create directory tree from §1.
- Root `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`.
- `.gitignore` (Java, Node, Rust, IDE, `demo-data/` except seed files).
- `scripts/setup-dev.sh` (toolchain check), `scripts/run-demo.sh` (`docker compose up --build`).
- Move existing `doc/` to `docs/` per monorepo convention.
- **Done when:** `./gradlew tasks` runs. ✅

### Step 1 - `java/shared/common` ✅ Done

- `ProblemDetail` (RFC 7807), `SynantonException` + subclasses (`AuthException`, `ForbiddenException`, `NotFoundException`, `ValidationException`).
- `JwtVerifier` - HS256 verify using Nimbus JOSE; constant-time comparison; expiry checked.
- `SubjectAssertion` - record `(subject, uid, gids, tenantId, exp)`.
- `TenantContext` - thread-local; populated from assertion or set anonymous for unauthenticated paths.
- `TenantContextFilter` - Spring `OncePerRequestFilter`; skip-list for `/auth/**`, `/actuator/**`.
- Unit tests: JWT round-trip, expired, wrong secret, malformed.
- **Done when:** `./gradlew :java:shared:common:test` is green. ✅

### Step 2 - `java/security` ✅ Done

- Spring Boot app (port 8081), `/auth/login`, `/auth/validate`, `/capabilities`.
- `HtpasswdBackend` - reads `username:bcrypt_hash:uid:gid1,gid2` flat file; verifies with `at.favre.lib:bcrypt`.
- `AuthService` - delegates to backend, issues HS256 JWT with `sub`, `uid`, `gid`, `tenant_id`, `exp`.
- `GlobalExceptionHandler` - maps `AuthException` → structured RFC 7807 `ProblemDetail` response.
- Unit tests: valid login, wrong password, validate round-trip.
- `FsPermissionGuard` - in `java/shared/common`, checks POSIX `(uid, gids)` against file mode bits; `checkRead` / `checkWrite` throw `ForbiddenException(ERR_FS_PERMISSION)`.
- `deployment/docker/security.Dockerfile` - multi-stage Gradle → `eclipse-temurin:21-jre-alpine`.
- **Done when:** `./gradlew :java:security:bootJar` produces a runnable JAR. ✅

### Step 3 - `java/topology` ✅ Done

- Spring Boot app (port 8082), Flyway migration `V1__init.sql` creating `topology.organizations`, `topology.users`, `topology.acl_grants`.
- `FilesystemAclSeeder` - walks `/data/ontologies` at startup, reads POSIX mode + owner/group, inserts `acl_grants` rows with `source = 'FS_BOOTSTRAP'`.
- REST endpoints: `GET /topology/users`, `GET /topology/users/{uid}/grants`, `POST /topology/acl/reseed`, `GET /capabilities`.
- `@JdbcTest` integration tests (H2, no Testcontainers): upsert user, insert grant, delete by source.
- `deployment/docker/topology.Dockerfile` - multi-stage Gradle → `eclipse-temurin:21-jre-alpine`.
- **Done when:** `./gradlew :java:topology:test` is green. ✅

### Step 4 - `java/syntology` ✅ Done

- Migrate from H2 to PostgreSQL (rename existing Flyway migration; add `syntology` schema prefix).
- Replace `MockTenantFilter` with `TenantContextFilter` from `shared/common`.
- Integrate `FsPermissionGuard` on every mutating call (from `security` module).
- Keep all existing endpoints; no behaviour change for read paths.
- Dockerfile (replaces standalone JAR).
- **Done when:** `GET /api/v1/ontology/graph?version=1.0.0` returns valid Cytoscape JSON against the Compose stack.

### Step 5 - `ui/syntology-admin` ✅ Done

- Add missing pages to the existing SPA: `Dashboard`, `GrantsView`, `Settings`.
- Wire `LoginPage` to the real `/auth/login` endpoint (currently targets standalone syntology).
- Update `ontologyApi.ts` paths to go through the nginx proxy.
- nginx Dockerfile with reverse-proxy config for `/auth/*`, `/api/v1/*`, `/topology/*`.
- **Done when:** browser flow in §5 works end-to-end against the running compose stack.

### Step 6 - `deployment/docker/compose.yaml` ✅ Done

- `deployment/docker/compose.yaml` wiring all five services with `depends_on: condition: service_healthy`, named volumes, `.env` env injection.
- `deployment/docker/postgres/init.sql` - creates `topology` and `syntology` schemas and grants.
- Dockerfiles for `security`, `topology`, `syntology`, `syntology-admin`.
- `demo-data/users` - htpasswd seed file for alice, bob, admin (bcrypt hashes).
- `demo-data/ontologies/` - sample Turtle file with directory POSIX bits set.
- `.env.example` with placeholder secrets.
- **Done when:** `./scripts/run-demo.sh` brings the entire demo up from a clean checkout.

### Step 7 - `test/e2e` 🔲 Planned

- Playwright project (`pnpm` package inside `test/e2e`).
- Two scenarios: alice writes successfully; bob is denied with `403`.
- Run script `pnpm e2e` after `compose up` is healthy.
- **Done when:** both scenarios pass in CI.

---

## 7. Out of scope (explicit non‑goals)

The following are intentionally **not** built for the demo but listed so reviewers do not expect them:

- Kafka, Redis, Cassandra, Neo4j, GraphDB, RDF4J, vLLM, Temporal.
- MCP server inside syntology and Synton chat panel inside UI.
- gRPC servers and clients.
- OWL reasoning beyond Jena's built‑in RDFS rules.
- Outbound auth broker (RFC 8693), IdP amortization, MCP session revalidation.
- Cost attribution, anomaly detection, forecast engine, GitOps reconciler.
- Multi‑tenant isolation beyond a single hard‑coded `tenant_id = "demo"`.
- High‑availability deployment, K8s manifests, Helm charts.

These remain reserved in the directory tree (§1) and can be filled in subsequent iterations without restructuring the monorepo or breaking the demo.

---

## 8. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Jena TDB2 transactions don't compose with Postgres tx → metadata can drift from graph storage. | Acceptable for demo. Add `metadata_drift_total` counter and a CLI `synctl synt reconcile` task to detect/repair. |
| POSIX bits inside containers depend on uid/gid mapping between host and container. | Pin uid/gid in Dockerfiles (`useradd -u 1001 -g 1001 …`); document host requirements in `README.md`. |
| bcrypt + PAM split - htpasswd is enough for demo but doesn't show real PAM integration. | htpasswd is the default; PAM backend stub included but disabled unless `SECURITY_IDP_BACKEND=pam` is set. |
| Cytoscape performance on large ontologies. | Cap graph endpoint at 500 nodes for demo; fall back to table view above that. |
| Cross‑container clock skew breaking JWT `exp`. | Compose services share the host clock; explicitly set `TZ=UTC` everywhere. |

---

## 9. References

- `docs/Synanton Monorepo Design Document.md` - repository structure and build system.
- `docs/syntology/Syntology Ontology Management Module.md` - backend module design (§5, §6, §7, §8 are load‑bearing for this plan).
- `docs/syntology/Syntology Ontology Management Module UI.md` - SPA component structure and tech stack.
- `docs/synanton-design-final-merged.md` - platform reference (§4 topology, §19 syntology, §25 topology module, §26 security module, §29 Content Adapter SPI, §31 IdP Port, §33 Capability Descriptor).
