# Synanton Guides — site source

Business/operator-level guides (Search, Security, Ingestion, Deployment, Troubleshooting 101), built with [MkDocs Material](https://squidfunk.github.io/mkdocs-material/) and versioned with [`mike`](https://github.com/jimporter/mike).

This is deliberately separate from `docs/` — `docs/` holds the normative engineering references (architecture, implementation, operations); this directory holds the narrative, non-technical guide layer described in the guides' own "Go Deeper" links. See `docs/book/Ingestion and security processing guide.md` for the closest existing precedent in tone.

## Local development

```bash
cd docs-site
uv venv --python 3.12 --seed
source .venv/bin/activate
uv pip install -r requirements.txt
mkdocs serve            # http://127.0.0.1:8000, live-reloads on edit
```

Before pushing, validate strictly (this is also what CI runs):

```bash
mkdocs build --strict
```

## Publishing

Guide **source** lives here, in `platform`, but the built site is published to the org-wide Pages repo, [`synanton/synanton.github.io`](https://github.com/synanton/synanton.github.io), at `https://synanton.github.io/`. `.github/workflows/docs-site.yml`:

- On every PR touching `docs-site/**`, builds the site (`mkdocs build --strict`) to catch broken links/nav — nothing is published.
- On every push to `main`, additionally checks out `synanton/synanton.github.io` and runs `mike` *inside that checkout* (via `mike ... -F ../docs-site/mkdocs.yml`), so `mike` commits/pushes to `synanton.github.io`'s `gh-pages` branch rather than this repo's. `mike` manages that branch directly (each version in its own subfolder, plus a `latest` alias) — there's no separate `gh-pages` branch to hand-maintain.

### One-time setup

1. **Create a fine-grained personal access token** scoped to just the `synanton/synanton.github.io` repository, with **Contents: Read and write** permission (Settings → Developer settings → Personal access tokens → Fine-grained tokens on a GitHub account with write access to that repo).
2. **Add it as a secret on this repo** (`platform`): Settings → Secrets and variables → Actions → New repository secret, named `SYNANTON_IO_PAGES_TOKEN`.
3. **Run the workflow once** (push to `main`, or trigger `workflow_dispatch`) so it creates the `gh-pages` branch on `synanton/synanton.github.io`.
4. **Enable Pages on `synanton/synanton.github.io`**: Settings → Pages → Source: "Deploy from a branch" → `gh-pages` → `/ (root)`.

After that, every push to `main` here keeps `https://synanton.github.io/` up to date; no further manual steps.

### Backfilling the `1.22` version

CI only publishes `1.23` (the current baseline) automatically. To make the version switcher also offer `1.22`, run once, locally, from a checkout of the guide content as it existed for that design revision, with a local clone of `synanton.github.io` as your working directory (using a token with write access, e.g. via `gh repo clone synanton/synanton.github.io`):

```bash
cd /path/to/synanton.github.io   # separate local clone, NOT this repo
pip install -r /path/to/platform/docs-site/requirements.txt
mike deploy --push -F /path/to/platform/docs-site/mkdocs.yml -b gh-pages 1.22
```

This only needs to happen once per historical version you want selectable — new pushes to `platform`'s `main` continue to update `1.23`/`latest` without touching `1.22`.
