# Demo script — upload, search, provenance

One-page walkthrough for the current BM25 demo. The vector leg is intentionally
off (see `README.md`).

## Prerequisites

- The homelab cluster is running the slice: synflux `:30090`, synquest `:30083`
  on `192.168.10.30`.
- The P0 PDF (`refresher-algebra-calculus.pdf`, SHA-256 `41fefaab…`) has been
  ingested and the projection rebuilt.
- A shell with the `ui` repo on `main` checked out on the demo host.

## Start the UI

Create `apps/web/.env` (gitignored) with the demo identity and cluster targets:

```dotenv
DEMO_AUTH_USER=<demo-user>
DEMO_AUTH_PASSWORD=<demo-password>
DEMO_AUTH_TENANT=demo
SYNANTON_JWT_SECRET=<cluster JWT secret>
VITE_REAL_API=true
VITE_DEMO_AUTH_USER=<demo-user>
VITE_DEMO_AUTH_TENANT=demo
VITE_SYNFLUX_URL=http://192.168.10.30:30090
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
2. **Ingestion → Upload files** (or **Upload folder**). Choose the P0 PDF
   (`refresher-algebra-calculus.pdf`) or a small/medium book. Files upload
   sequentially.
   - The upload progress bar reaches 100%.
   - The row switches to **Processing** and polls the operation to terminal.
   - Expect `SUCCEEDED / FULL` for PDFs. EPUBs report `SUCCEEDED / PARTIAL`
     because the text adapter does not support tables/embedded images; that is
     the correct intent-vs-outcome status, not a failure.
3. **Search** for `linear algebra and calculus`.
   - Expect 20 results, `strategy=bm25`.
   - The top hit is **Matrix properties** (`bdf91543…:4`).
4. **Open Provenance** on the top hit. Point out:
   - `source` — the upload URI;
   - `source sha256` — `41fefaab…` (matches the PDF);
   - `chunk sha256` — `b6bb4b84…` for the top chunk;
   - `processing run` — the run id shown;
   - `source versions` — the version ids that produced the hit.
   The panel expands under the clicked row.
5. **Page ranges** — the panel states they are not exposed by the public `/v1`
   contract yet (#338). This is intentional; do not invent values.

The P0 PDF is already in the index, so uploading it demonstrates the full loop
and remains searchable. A **new** document is not searchable until the projection
is rebuilt:

```bash
curl -X POST -H "Authorization: Bearer <token>" \
  http://192.168.10.30:30083/v1/search-projection/rebuild
```

## What to say is not in the demo

- Demo auth is a placeholder replaced by #340; the token is configuration-driven,
  not a production identity path.
- Search is BM25-only. The vector leg is gated on gpu-runtime #23
  (decision date 2026-10-31).
- EPUBs are PARTIAL by design (unsupported tables/embedded images). The earlier
  publication failure on large chunks was fixed in #365.
- Page ranges are absent until #338.
- Very large files can hit the extraction sync-size ceiling; for the demo use the
  P0 PDF or a small/medium book.

## Reproducibility

Record the cluster image tags and revisions before presenting. As of
2026-10-21 the cluster ran synflux `slice-20261021c1f`, synquest
`slice-20261021c1c`, and extraction-gateway `slice-20261021c1`; UI `main`
contains the upload flow (#17), inline provenance, and URL-derived routing.
