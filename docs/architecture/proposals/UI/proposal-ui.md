# Synanton UI — Role-Aware Multi-Tool UI Platform

**File:** `proposal-ui.md`
**Status:** Draft for Review
**Owner:** TBD
**Reviewers:** Synanton architecture group, security reviewer

## 1. Summary

Synanton UI is a role-aware, permission-driven UI platform for the Synanton Knowledge Platform. It is not a single evaluation dashboard and not a production enterprise frontend. It is a shared shell that hosts multiple tools and composes navigation, tabs, and components according to the authenticated user's effective permissions.

## 2. Goals

- One UI platform, multiple role-specific experiences.
- Permission-driven composition, not role-driven `if admin` logic.
- Shared authentication, tenant context, design system, API clients, streaming infrastructure.
- Tools: Admin Tool, Client Tool, Evaluation Tool.
- Evaluation Tool as first priority for the research loop.
- Web (Next.js) and Mobile (Flutter) clients.
- Backend authorization remains authoritative.

## 3. Non-Goals

- Separate `synanton/admin`, `synanton/client`, `synanton/evaluation` repositories at this stage.
- Production enterprise frontend replacement.
- Frontend as authorization boundary.

## 4. Layered Architecture

text

```
Synanton UI
 ├── UI Platform
 │    ├── Auth / Session
 │    ├── Tenant Context
 │    ├── Permissions
 │    ├── Navigation
 │    ├── Design System
 │    └── API / Streaming
 ├── Tools / Applications
 │    ├── Admin Tool
 │    ├── Client Tool
 │    └── Evaluation Tool
 └── Reusable Components
      ├── SearchResults
      ├── DocumentViewer
      ├── CitationPanel
      ├── GraphViewer
      ├── MetricsTable
      └── ...
```



## 5. Capability Model

Roles are bundles of capabilities. The backend returns a flat array of effective permissions for the current tenant:

json

```
{
  "tenant_id": "tenant-7",
  "effective_permissions": [
    "search.execute",
    "documents.read",
    "graph.read",
    "experiments.read",
    "metrics.read"
  ]
}
```



Example permissions:

text

```
users.read
users.write
tenants.read
ontology.read
ontology.write
search.execute
documents.read
graph.read
experiments.read
benchmarks.read
metrics.read
```



The UI never computes permissions from roles.

## 6. Tools

| Tool            | Purpose                | Example permissions                                        |
| --------------- | ---------------------- | ---------------------------------------------------------- |
| Admin Tool      | Manage platform        | `users.*`, `tenants.read`, `ontology.*`, `security.manage` |
| Client Tool     | Use knowledge platform | `search.execute`, `documents.read`, `graph.read`           |
| Evaluation Tool | Measure platform       | `experiments.read`, `benchmarks.read`, `metrics.read`      |

## 7. Evaluation Tool — Priority

The Evaluation Tool consumes Result Manifests from the Benchmark Runner. It must support:

- list benchmark runs,
- filter by benchmark manifest, Synanton commit, date, status,
- compare two or more runs (BM25 / Vector / Hybrid),
- drill down to full Result Manifest and linked Benchmark Manifest,
- show failed runs, not hide them,
- export CSV/JSON,
- link to artifacts.

MVP view:

text

```
Experiment: hybrid-search-001
Corpus: 10,000,000 documents
Generator: EventLab 0.1
Seed: 123456
Ingestion: Lucentrix / 16 workers

              BM25      Vector     Hybrid
Latency       42 ms     67 ms      51 ms
Recall        ...       ...        ...
Index size    ...       ...        ...
```



## 8. Security & Audit

**Hard architectural rule:** UI permission checks control visibility and UX only. They do not replace backend authorization. The Policy Enforcement Point is the API gateway and domain services.

Audit logging must be structured:

text

```
timestamp, user_id, action, target_type, target_id,
correlation_id, client_ip, user_agent, tenant_id, outcome
```



Use `X-Correlation-ID` across UI, gateway, and services.

## 9. Code Patterns

### Next.js

tsx

```
// ui/web/hooks/usePermission.ts
import { useSession } from '@/shared/auth';

export function usePermission(requiredPermission: string): boolean {
  const { data: session } = useSession();
  if (!session?.effective_permissions) return false;
  return session.effective_permissions.includes(requiredPermission);
}

// ui/web/components/Guard.tsx
export function Guard({ permission, fallback = null, children }) {
  const hasAccess = usePermission(permission);
  return hasAccess ? <>{children}</> : <>{fallback}</>;
}
```



### Flutter

Use `ConsumerWidget` and `ref.watch`; avoid `ProviderScope.containerOf(context).read(...)` inside build.

dart

```
class RunBenchmarkButton extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final perms = ref.watch(permissionsProvider);
    final canRun = perms.contains('experiments.write');
    return ElevatedButton(
      onPressed: canRun ? _launchBenchmark : null,
      child: Text(canRun ? 'Run Benchmark' : 'Access Denied'),
    );
  }
}
```



## 10. Versioning

- UI platform versioned independently.
- Tool modules versioned with the platform for now.
- Manifest schemas versioned separately.
- UI must declare which Result Manifest schema versions it can render.

## 11. Open Questions

1. What is the exact manifest API the UI consumes?
2. How are tenant-scoped benchmarks filtered?
3. What is the minimum viable Evaluation Tool for first release?
4. Should Admin and Client tools follow immediately or later?

## 12. Review Checklist

- □  

  Permission schema frozen.

- □  

  `effective_permissions` contract agreed with backend.

- □  

  Evaluation Tool consumes Result Manifests.

- □  

  Failed runs visible.

- □  

  Audit log structured.

- □  

  Backend authorization rule documented in repository root.