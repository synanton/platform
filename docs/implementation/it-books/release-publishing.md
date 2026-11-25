# IT Books ontology release publishing

**Workstream:** A3 (#899) · **Accepted architecture:** #900 / `0098f84b`

IT Books is published as an immutable Syntology ontology release alongside the existing Supply
Chain ontology. Publishing activates only the `it-books` ontology; it does not deprecate or replace
Supply Chain's active release.

## Identity

Each release stores:

- `releaseId` — immutable per-release reference (`uuid:<version_id>`)
- `releaseDigest` — SHA-256 over the canonical compiled release components
- `ontologyId` — `it-books`
- version and activation timestamp

Reloading the stored release returns the same `releaseId` and `releaseDigest`. A downstream
compiler must use these fields, not a mutable label or "active" alias.

## Publish

```bash
SYNTOLOGY_URL=http://localhost:8080 \
TENANT=demo \
VERSION=it-books-v1 \
TOKEN="$SYNTOLOGY_TOKEN" \
./scripts/it-books/publish-release.sh
```

The script uploads `schemas/it-books/v1/schema.hcl` through the existing HCL release path and then
queries:

```text
GET /v1/tenants/{tenant}/active-ontology-version?ontologyId=it-books
```

Without `ontologyId`, the endpoint continues to resolve the Supply Chain active release.

The immutable compiler read path is separate from active selection:

```text
GET /v1/tenants/{tenant}/ontology-releases/{releaseId}
```

It returns the release's ontology, version, releaseId, releaseDigest, status and createdAt even
after that release is no longer active. The P0 compiler addresses this endpoint by releaseId and
never reads the active alias.

## Verify

```bash
curl -sS "http://localhost:8080/v1/tenants/demo/active-ontology-version?ontologyId=it-books" \
  -H "X-Tenant-ID: demo"
curl -sS "http://localhost:8080/api/v1/admin/ontology/schemas/it-books-v1" \
  -H "X-Tenant-ID: demo"
```

The first response must carry the release identity; the second must expose the stored IT Books IR.
The default active endpoint must still report `ontologyId: supply-chain`.

## Boundaries

- Publishing a newer IT Books release deprecates only the previous IT Books release.
- Historical release rows retain their original `releaseId`/`releaseDigest`.
- No compiler, export profile, extraction, UI, or canonical graph behavior is part of A3.
- No Docling runtime or model dependency.
- No F1/gold/gate/profile/ADR-044 change.
