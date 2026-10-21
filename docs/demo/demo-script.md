# Demo script — ingestion, search, provenance

One-page walkthrough for the current BM25 demo. The vector leg is intentionally
off (see `README.md`).

## Prerequisites

- The homelab cluster is running the slice: synflux `:30090`, synquest `:30083`
  on `192.168.10.30`.
- The P0 PDF (`refresher-algebra-calculus.pdf`, SHA-256 `41fefaab…`) has been
  ingested and the projection rebuilt.
- A shell with the `ui` repo checked out on the demo host.

## Start the UI

Create `apps/web/.env` (gitignored) with the demo identity and cluster target:

```dotenv
DEMO_AUTH_USER=<demo-user>
DEMO_AUTH_PASSWORD=<demo-password>
DEMO_AUTH_TENANT=demo
SYNANTON_JWT_SECRET=<cluster JWT secret>
VITE_REAL_API=true
VITE_DEMO_AUTH_USER=<demo-user>
VITE_DEMO_AUTH_TENANT=demo
VITE_SYNQUEST_URL=http://192.168.10.30:30083
```

`SYNANTON_JWT_SECRET` is server-side; never put it in a `VITE_*` variable.
If any required `DEMO_AUTH_*`/secret is missing, the login endpoint returns
`503 DEMO_AUTH_NOT_CONFIGURED` and issues no token.

```bash
cd ui
pnpm install
pnpm --filter @synanton/web dev --host 0.0.0.0
```

Restart the dev server after changing `.env`; Vite reads these values at startup.
Without `VITE_REAL_API=true`, the UI intentionally runs the mock shell.

Open the printed URL.

## Steps

1. **Sign in** with the configured demo user/password. The tenant comes from
   `DEMO_AUTH_TENANT`; there is no tenant field in the form.
2. **Search** for `linear algebra and calculus`.
   - Expect 20 results, `strategy=bm25`.
   - The top hit is **Matrix properties** (`bdf91543…:4`).
3. **Open Provenance** on the top hit. Point out:
   - `source` — the upload URI;
   - `source sha256` — `41fefaab…` (matches the PDF);
   - `chunk sha256` — `b6bb4b84…` for the top chunk;
   - `processing run` — the run id shown (for example `2107cea9…`);
   - `source versions` — the version ids that produced the hit.
4. **Page ranges** — the panel states they are not exposed by the public `/v1`
   contract yet (#338). This is intentional; do not invent values.

## What to say is not in the demo

- Demo auth is a placeholder replaced by #340; the token is configuration-driven,
  not a production identity path.
- Search is BM25-only. The vector leg is gated on gpu-runtime #23
  (decision date 2026-10-31).
- EPUB extraction works, but platform publication of the tested EPUB fails on a
  Cassandra batch limit (#359); it is a known limitation, not the demo path.
- Page ranges are absent until #338.

## Reproducibility

Record the cluster image tags and revisions before presenting. As of
2026-10-21 the cluster ran synflux `slice-20261021c1e`, synquest
`slice-20261021c1c`, and extraction-gateway `slice-20261021c1`; the merged main
contains #344/#345/#346/#347/#358/#360.
