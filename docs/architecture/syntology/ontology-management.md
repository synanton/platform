# Syntology Ontology Management Module and UI – v1.17 (Standalone Demo Focus)

**Version:** 1.17
**Date:** 2026-07-04
**Status:** Final – Design for Standalone Demo
**Audience:** Architects, Developers, Demo Stakeholders
**Alignment:** Synanton Platform v1.17 (Merged Reference) – with reduced scope for a functional standalone deployment

------

## 1. Introduction and Context

This document specifies the design, implementation, and demonstration plan for the **Syntology Ontology Management Module** and its accompanying **User Interface**, aligned with the Synanton Platform v1.17 architecture. The primary goal is to deliver a **standalone demo** that showcases the module’s core capabilities-ontology versioning,  entity/relation resolution, graph visualisation, and MCP  integration-without the full complexity of a production deployment.

The demo is intended to validate the module’s value, gather stakeholder  feedback, and serve as a foundation for future production‑ready  iterations. It deliberately reduces scope to keep the build manageable  while still demonstrating the module’s strategic role in the platform.

------

## 2. Alignment with Synanton v1.17

The `syntology` module adheres to the platform’s architectural principles, as defined  in the Synanton v1.17 design. However, for the demo, we adopt a **minimal viable implementation** that still respects the core principles:

| Principle                       | Implementation in Demo                                       |
| ------------------------------- | ------------------------------------------------------------ |
| **Unified Identity**            | Module is named `syntology` across all contexts; code package `org.synanton.syntology.*`. |
| **Hexagonal Architecture**      | Ports & Adapters are used; the domain is isolated from REST/gRPC/MCP and storage. |
| **Honest Capability Surfacing** | The module exposes `/capabilities` with a reduced feature set (no reasoning, limited validation). |
| **Cost Awareness**              | Cost attribution is stubbed (log events) but not wired to the full `api_usage` pipeline. |
| **MCP Integration**             | MCP tools are implemented as a first‑class interface for the Synton agent. |
| **Security & Multi‑tenancy**    | Simplified: a single hard‑coded tenant, no real authentication (mock JWT). |
| **GitOps**                      | Ontology **schemas** are HCL files in Git (`schemas/ontology/`). Load a zip bundle or local checkout via Admin API (`POST /api/v1/admin/ontology/schemas`). Control-plane tenant-policy GitOps is separate. |

The demo does not depend on other platform modules (`security`, `topology`, `control-plane`, `relix`) except for minimal stubs. This allows it to run standalone, with only a local database (Jena TDB2) and an embedded web server.

------

## 3. Module: Syntology (v1.17 – Reduced Scope)

### 3.1. Role and Responsibilities

**Role:** Manage ontology (TBox) definitions – entity types, relation types, properties, and versions.

**Responsibilities (Demo Scope):**

- Store and version ontology definitions (in Jena TDB2).
- Resolve entities and relations by `(tenant, label, version)`.
- Support basic version management (list, create, switch active version).
- Provide REST API for administration and graph fetching.
- Expose MCP tools for the Synton agent (list ontology, get entity, create entity/relation – with validation stubs).
- Emit ontology change events to a log file (Kafka is stubbed).

**Out of Scope for Demo:**

- SHACL validation (simplified to basic property checks).
- OWL reasoning (no inference, only stored axioms).
- Full multi‑tenancy (single tenant `demo`).
- Session pinning (simplified version selection via UI).
- Integration with `relix` (no graph rebuild).
- Cost attribution (only logging).

### 3.2. Component Diagram (Standalone)

text

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Standalone Demo Container                          │
│                                                                             │
│  ┌───────────────┐   ┌───────────────┐   ┌───────────────────────────────┐ │
│  │  REST API     │   │  MCP Server   │   │  Graph Visualisation (UI)      │ │
│  │  (Spring Web) │   │  (SSE/HTTP)   │   │  (React + Cytoscape)           │ │
│  └───────┬───────┘   └───────┬───────┘   └───────────────┬───────────────┘ │
│          │                   │                           │                 │
│          └───────────────────┼───────────────────────────┘                 │
│                              ▼                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │                     Semantic Core (synt-domain)                      │ │
│  │  – OntologyService (resolve, version bump, list)                    │ │
│  │  – TenantOntologyRouter (single tenant)                             │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                              │                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │                     OntologyAdapter (SPI)                            │ │
│  │  – JenaTdb2Adapter (embedded TDB2)                                  │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
```



### 3.3. Interfaces (Ports) – Demo

**Inbound (Primary):**

1. **REST API (Spring MVC)** – endpoints:

   - `GET /api/v1/ontology/versions` – list all versions.
   - `POST /api/v1/ontology/versions` – create a new version (from uploaded Turtle file).
   - `GET /api/v1/ontology/entities?version=&label=` – resolve entity.
   - `GET /api/v1/ontology/relations?version=&label=` – resolve relation.
   - `GET /api/v1/ontology/graph?version=` – return full graph data (nodes/edges) for visualisation.
   - `GET /api/v1/ontology/capabilities` – return capability matrix.
   - `POST /api/v1/ontology/validate` – SHACL validation when `shapes.ttl` exists for the active version; otherwise label+URI check.
   - `POST /api/v1/admin/ontology/schemas` – compile an HCL bundle (zip or `.hcl`) through JSON IR into OWL Turtle + SHACL and persist a version.
   - `POST /api/v1/admin/ontology/schemas/preview` – same compile, no persist.
   - `POST /api/v1/admin/ontology/schemas/from-path` – load from `syntology.schema.git-root` + relative path.
   - `GET /api/v1/admin/ontology/schemas/{version}` – stored JSON IR.

2. **MCP Server (STREAMABLE_HTTP)** – tools:

   - `syntology.list_ontology` – returns all entities/relations for a given version.
   - `syntology.get_entity` – returns details of a specific entity.
   - `syntology.create_entity` – stub: creates a new entity in the current version (if allowed).

   The MCP server is hosted on a separate path (`/mcp`) and uses the same tenant context.

**Outbound (Secondary):**

1. **OntologyAdapter SPI** – interface:

   java

   ```
   public interface OntologyAdapter {
       void init(String storagePath);
       OntologyGraph loadOntology(String tenant, String version);
       void persistOntology(String tenant, String version, OntologyGraph graph);
       boolean versionExists(String tenant, String version);
       void deleteVersion(String tenant, String version);
       boolean supportsFeature(Feature feature);
   }
   ```

   

   - In demo, `JenaTdb2Adapter` implements this using Apache Jena TDB2.
   - Features supported: `BASIC_GRAPH_STORAGE`, `VERSIONING`, `SHACL_VALIDATION`. Not supported: `OWL_REASONING`.
   - HCL load writes `ontology.ttl`, `shapes.ttl`, and `schema.json` beside the existing per-version directory.

2. **Event Logger** – instead of Kafka, ontology change events are logged to a file (`syntology-events.log`) with timestamp and event type.

### 3.4. Data Model (Simplified)

**Relational (H2 in-memory) – for metadata only** (in demo we can also store metadata in the graph, but to keep it simple we use a small SQL table for versions).

sql

```
CREATE TABLE ontology_versions (
    version_id   UUID PRIMARY KEY,
    tenant_id    VARCHAR(64) DEFAULT 'demo',
    version      VARCHAR(32) NOT NULL UNIQUE,      -- semantic version, e.g. "1.0.0"
    label        VARCHAR(255),
    description  TEXT,
    graph_uri    VARCHAR(512),                     -- path to TDB2 dataset
    status       VARCHAR(20) DEFAULT 'ACTIVE',    -- ACTIVE, DEPRECATED
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```



The actual ontology graphs are stored as Jena TDB2 datasets in a local directory (e.g., `./data/syntology/tdb2/{tenant}/{version}/`).

**Entity and relation structures** are represented as RDF triples in the graph; the domain model (`EntityType`, `RelationType`) is built by querying the graph via SPARQL.

### 3.5. Key Algorithms (Simplified)

- **Entity Resolution:**
  - Query TDB2 with SPARQL `SELECT ?uri ?label ?props WHERE { ?uri rdfs:label ?label ... }`.
  - Cache results in a local `LoadingCache` (Caffeine) with TTL 600s.
- **Version Bump:**
  - Admin uploads a Turtle file via REST.
  - The adapter stores it as a new dataset under the new version.
  - The metadata row is inserted, and the previous version is marked `DEPRECATED`.
- **Graph Fetch (for UI):**
  - A SPARQL query retrieves all `rdfs:Class` and `rdf:Property` nodes, plus `rdfs:subClassOf` and `rdfs:domain`/`range` edges.
  - The result is transformed into a JSON object with `{nodes: [{id, label, type}], edges: [{source, target, label}]}`.

### 3.6. Configuration (Key Settings)

All keys prefixed with `syntology.`.

| Key                                  | Default                 | Description                                  |
| ------------------------------------ | ----------------------- | -------------------------------------------- |
| `syntology.storage.adapter`          | `jena`                  | Adapter type (only `jena` supported in demo) |
| `syntology.storage.jena.path`        | `./data/syntology/tdb2` | Root directory for TDB2 datasets             |
| `syntology.cache.entity_ttl_seconds` | 600                     | Entity cache TTL                             |
| `syntology.mcp.server.path`          | `/mcp`                  | MCP server endpoint path                     |
| `syntology.tenant.default`           | `demo`                  | Fixed tenant ID for demo                     |

### 3.7. Metrics (Stubbed)

Metrics are emitted to standard logs for demonstration; no Prometheus integration in demo.

- `syntology_resolve_duration_seconds` (log histogram)
- `syntology_cache_hit_total` (counter)
- `syntology_version_bump_total` (counter)
- `syntology_adapter_errors_total` (counter)

------

## 4. UI: Syntology Ontology Management Interface (Demo)

### 4.1. Purpose and Scope

The UI provides a graphical interface to interact with the ontology. For the demo, we focus on **browsing**, **version management**, and **basic visualisation**. The UI is a React SPA served from the same Spring Boot application (via static resources). It communicates with the backend via REST APIs.

### 4.2. UI Features (Demo)

| Feature                     | Description                                                  |
| --------------------------- | ------------------------------------------------------------ |
| **Version Selector**        | Dropdown to choose an ontology version; automatically refreshes the graph. |
| **Graph Visualisation**     | Interactive graph using Cytoscape.js: nodes (classes, properties) and edges  (subclass, domain/range). Supports zoom, pan, and node dragging. |
| **Entity/Relation Details** | Click on a node to open a side panel showing URI, label, properties, supertypes, and inverse relations. |
| **Search**                  | Text input to filter entities by label or URI; the graph highlights matches. |
| **Admin Panel**             | Page for uploading a new Turtle file and creating a new version. Displays list of existing versions with status. |
| **Basic Login**             | A mock login screen where any username logs in as “demo” tenant with editor permissions (no real authentication). |

**Out of Scope:**

- SHACL validation UI.
- AI chat (Synton integration) – MCP tools are tested separately using a CLI.
- Multi‑tenant UI (single tenant).
- Session pinning (version selection only).

### 4.3. Technology Stack (Demo)

| Category         | Technology                                        |
| ---------------- | ------------------------------------------------- |
| Framework        | React 18 + TypeScript                             |
| Build tool       | Vite                                              |
| State management | React Context + useState (no Redux)               |
| HTTP client      | `fetch` with Axios interceptor for tenant header  |
| Graph rendering  | Cytoscape.js (with CoLa layout)                   |
| Styling          | Tailwind CSS                                      |
| Testing          | Vitest + React Testing Library (basic unit tests) |

### 4.4. UI Architecture

text

```
src/
├── App.tsx
├── main.tsx
├── pages/
│   ├── Login.tsx
│   ├── OntologyViewer.tsx       # main page with graph and details
│   └── Admin.tsx                # version management
├── components/
│   ├── GraphCanvas/             # Cytoscape wrapper
│   ├── DetailsPanel/            # node details
│   ├── VersionSelector/         # dropdown
│   ├── SearchBar/               # filter input
│   └── Layout/                  # header, sidebar
├── services/
│   ├── ontologyApi.ts           # REST calls
│   └── auth.ts                  # mock authentication
├── hooks/
│   ├── useOntology.ts           # fetch graph data
│   └── useTenant.ts             # get current tenant
├── types/
│   └── ontology.ts              # TypeScript interfaces
└── utils/
    └── graphTransformer.ts      # convert REST response to Cytoscape format
```



### 4.5. Data Flow

1. User logs in (mock) → sets tenant `demo` and a mock token.
2. The main page loads → `useOntology` fetches `/api/v1/ontology/graph?version=active`.
3. The response (nodes & edges) is transformed into Cytoscape elements and rendered.
4. Click on node → fetches entity details (if not already cached) and displays in side panel.
5. Admin page: list versions from `/api/v1/ontology/versions`, upload file → POST to `/api/v1/ontology/versions` with multipart form.

### 4.6. State Management

- **Global state:** a simple React Context holds the current version, selected node, and graph data.
- **Data fetching:** `useEffect` + `fetch` with caching; no React Query for simplicity.
- **UI state:** local state for search term, sidebar open.

------

## 5. MCP Integration (Demo)

The module acts as an MCP server (STREAMABLE_HTTP) on `/mcp`. The server implements the following tools:

| Tool                      | Description                                             | Request/Response Example                                     |
| ------------------------- | ------------------------------------------------------- | ------------------------------------------------------------ |
| `syntology.list_ontology` | Returns all entities and relations for a given version. | `{"version": "1.0.0"}` → `{"entities": [...], "relations": [...]}` |
| `syntology.get_entity`    | Returns details of a single entity.                     | `{"version": "1.0.0", "label": "Product"}` → `{"uri": "...", "properties": [...]}` |
| `syntology.create_entity` | (Stub) Creates a new entity in the current version.     | `{"label": "Supplier", "superType": "Organization"}` → `{"status": "created", "uri": "..."}` |

The MCP server uses the same `OntologyService` as the REST API. Authentication is not enforced; the tenant is hard‑coded to `demo`. This allows quick testing with any MCP client (e.g., `mcp-cli`).

------

## 6. Demo Deployment Plan

### 6.1. Build and Run

The demo is packaged as a single Spring Boot JAR with an embedded H2  database and Jena TDB2 storage. The UI is built and copied into `src/main/resources/static/` during the Gradle build.

**Steps:**

1. Clone the monorepo and navigate to `java/syntology`.
2. Run `./gradlew clean build` – this compiles the Java code, runs the Gradle task to build the UI (using `pnpm build`), and copies the UI to `build/resources/main/static`.
3. Run `./gradlew bootRun` – starts the Spring Boot app on port 8080.
4. Access `http://localhost:8080` – login with any username (no password).
5. A sample ontology (Supply Chain) is pre‑loaded on startup.

### 6.2. Sample Ontology

A small ontology with ~20 classes (e.g., Product, Supplier, Warehouse,  Order, etc.) and a few properties (hasSupplier, locatedIn, etc.) is  provided as a Turtle file in `src/main/resources/sample-ontology.ttl`. It is loaded at first start if no version exists.

### 6.3. MCP Testing

Use the `mcp-cli` tool (or any MCP client) to connect to `http://localhost:8080/mcp` and call the tools interactively.

### 6.4. Demo Script

For a live demo, the presenter can:

1. Show the login and main graph view.
2. Click on a node and display its details.
3. Search for a concept.
4. Switch to a different version (if multiple exist).
5. Go to the Admin page, upload a new Turtle file, and create a new version.
6. Show that the graph updates accordingly.
7. Optionally, use MCP CLI to list ontology and get entity.

------

## 7. Limitations and Future Roadmap

The demo is intentionally limited. Planned enhancements for production:

- Full integration with `security` (real authentication, tenant resolution).
- Integration with `relix` (ontology‑triggered graph rebuilds).
- SHACL validation UI (API + HCL→SHACL compiler shipped).
- OWL reasoning (materialised or on‑demand).
- Session pinning per user.
- Control-plane GitOps reconciler for ontology trees (operators/CI call the Admin API today).
- Event emission to Kafka.
- Full cost attribution and observability.

------

## 8. Conclusion

This design provides a clear, achievable plan for a standalone demo of the  Syntology Ontology Management Module and UI, aligned with the Synanton  v1.17 architecture. It demonstrates the core value-ontology versioning,  graph visualisation, and MCP integration-with minimal dependencies,  enabling rapid feedback and iterative development. The modular design  ensures that the demo can evolve into a production‑ready component  without major rework.