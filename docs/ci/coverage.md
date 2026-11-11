# CI coverage

This is the test matrix enforced by the repository workflows.

## Pull-request and main gates

| Workflow | What it covers | Notes |
|---|---|---|
| `gradle-build.yml` / Build All Modules | `./gradlew buildAll` across active Java modules, including contract, storage, resolution, and extraction tests | Excludes the three Postgres Testcontainers suites below because the GitHub runner runs out of memory (exit 137). |
| `gradle-build.yml` / Replica Read Parity | `content-cache-postgres` replica-read parity against a primary + streaming replica | Runs separately with the Postgres replica script. |
| `local-real-demo.yml` | Isolated Compose acceptance: upload → search → provenance and RAKG plumbing | Uses the local-real-demo profile; not a K8s gate. |
| `extraction-contract-mirror.yml` | Mirrored `synanton.extraction.v1` protos match the sibling extractor repository | Skips when the peer checkout is absent. |
| `gpu-contract-mirror.yml` | Mirrored `synanton.gpu.v1` protos match the sibling gpu-runtime repository | Skips when the peer checkout is absent. |
| `openapi-lint.yml` | Vacuum lint for changed `docs/api/**` specs | PR-oriented. |
| `docs-ru-staleness.yml` | English source pins behind Russian translations | Runs when `docs/**` or `docs-ru/**` changes. |
| `prometheus-validation.yml` | `promtool check rules` and runbook-link presence for changed Prometheus rules/runbooks | Narrow path filter; no full-repo run. |
| `unicode-control-check.yml` | Rejects bidi/zero-width controls in changed files | PR-oriented. |

## Explicit exclusions from `buildAll`

These suites are intentionally excluded from the main `buildAll` job because
their Testcontainers Postgres workloads exceed the runner memory budget:

- `:java:content-cache-postgres:test`
- `:java:synvault-postgres:test`
- `:java:synquest-postgres:test`

They remain in the repository and run in a database-enabled environment
(including `Replica Read Parity` where applicable and #287's live-test track).
They are not silently removed from the project build.

## Known follow-ups

- Unicode scanner false positives for ZWJ/ZWNJ need a context-aware allowlist.
- Add per-job timeouts to workflows that currently rely on the default limit.
- Document cross-repository contract update ordering for the extraction and GPU
  mirror checks.
