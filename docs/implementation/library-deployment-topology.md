# Personal library deployment topology

**Status:** Recommendation recorded 2026-10-20 — not implemented
**Related:** Lucentrix #16 (filesystem crawler), Platform #328 (folder upload), platform #331 (legacy-path acceptance finding), [`deployments/homelab/README.md`](../../deployments/homelab/README.md)

This records where each piece of the personal-library capability runs, and why. It is a deployment decision, not a change to the ingestion contract.

## Placement

| Component | Placement | Why |
|---|---|---|
| Platform API (`synflux`, `/v1/...`) | k8s | Already deployed per the homelab slice; no separate deployment |
| Web upload (#328) | k8s | Part of the platform API (`/v1/ingestion-runs` on synflux); no separate service |
| UI | k8s (after it works standalone) | Ingress + TLS is the natural fit for "reachable from any device" |
| Lucentrix crawler | Standalone on M70q | It reads local files; the data and cursor state live there |

## Why the crawler stays on M70q

**Data locality.** The crawler reads `~/books/`, which lives on M70q. Running it in k8s means mounting that filesystem into the cluster (NFS or synced PVC), which is fragile for a batch job — the laptop sleeps, the mount drops, the sync lags.

**Batch, not service.** It runs periodically and exits. A systemd timer on M70q is simpler than a k8s CronJob + PVC + cursor management.

**State travels with the crawler.** `FileJsonPersistence` writes the cursor/fingerprint state next to the crawler. That belongs on the machine that runs it, not on a PVC that must be backed up separately.

**The network already exists.** The 10 Gbit mesh connects M70q to the cluster; the crawler uses the same ingress path as the UI.

## Why the UI goes in k8s

- **Reachability:** laptop, phone, tablet through one ingress + DNS.
- **Small footprint:** a static SPA behind nginx is negligible against the workers' 192 GB RAM.
- **TLS once:** the ingress certificate covers the API and the UI.
- **Standard shape:** Deployment + ConfigMap (nginx SPA fallback) + Service + Ingress.

Do **not** deploy the UI to k8s until it works standalone. For the first UI slice, `npm run dev` on M70q against the cluster API via port-forward is the faster loop; deploy when there is something worth showing on a phone.

## The pivot: where does the library live?

| Library location | Crawler placement |
|---|---|
| M70q (`~/books`) — the personal-library default | Standalone on M70q |
| NAS / dedicated storage with a k8s-visible mount | k8s CronJob + PVC (same image, same config) |
| Built entirely via web upload (no pre-existing files) | Not needed for M1; #328 handles it |

If the library moves, the crawler follows it — the migration is mechanical.

## What not to do

| Anti-pattern | Why |
|---|---|
| NFS-mount M70q's `~/books` into the cluster so the crawler runs "in k8s" | Adds a network dependency for a batch job; sleep/mount drops break the scan |
| Containerize the crawler just for uniformity | It is a CLI with local state; systemd is the right tool |
| Add a second ingress for the crawler | Same endpoint and certificate as the UI |
| Build a "deploy UI to cluster" step for M1 | The SPA build does not care where it runs; dev server iterates faster |

## Network path

```text
M70q (crawler) --10G mesh--> Ingress on cluster --> Platform API (synflux /v1/...)
Browser (any device) --> Ingress --> UI Service --> SPA calls Platform API
```

Both paths use the same ingress and auth boundary. The crawler uses a service credential; the UI uses a user JWT. Both are valid auth paths for `/v1/ingestion-runs`.

## Migration triggers

| Trigger | Action |
|---|---|
| Library moves to NAS / dedicated storage | Crawler → k8s CronJob + PVC |
| Multiple users, each with their own library | Per-tenant crawler (CronJob + PVC per tenant) |
| Crawler must run continuously (streaming source) | Reconsider the batch assumption |
| UI needs SSR or backend logic | UI becomes a service, still in k8s |

## Note on the acceptance finding

The crawler must target the **`/v1/sources` + `/v1/ingestion-runs`** contract on the k8s synflux, not the legacy `/content/{tenant}` + `/ingest` path. The legacy path failed the #16 acceptance run (missing `SourceVersion`, lost MIME); see platform #331 and Lucentrix #19. Slice 4 is the migration that makes the "crawler on M70q → API in k8s" topology true at the wire.
