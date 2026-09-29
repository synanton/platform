# CLI.1 — Documented-Run Runner Spec

Smallest change that fixes documented-run invocation: runs leave the test
worker (plain `java -cp`, no Gradle).

## Invocation

```bash
setsid java -Xmx10g -cp <runtime-cp> <leg-main> --corpus /tmp/corpus-v1 \
  --out runs/ > /tmp/<leg>-run.log 2>&1 < /dev/null &
echo $!  # PID captured at launch; single-PID kill only, never pkill -f
         # (JVM command lines match too many patterns — friendly fire)
```

Detached runs must use `setsid`, not `nohup`: nohup detaches from the
controlling terminal but not from the session, and session teardown still
kills it. `setsid` creates a new session and is immune. (Learned 2026-09-28
when a nohup'd run died with the tool session.)

## Checkpoint granularity: per query

Query phase allocates more than load (readers + HNSW resident + metadata —
512m OOM'd at query 100/120 while load survived it). Checkpoint flushes after
every query: a crash loses at most one query, never 99. "Checkpoint at end of
query phase" is rejected for the same reason.

## No index cache

Documented runs always cold-build the index. Any future cache flag is
dev-only, labeled, and recorded in output — a cached-index run is not a
documented run.

## Build artifact

Each leg emits `runs/<leg>-v1.build.json` (build ms, index bytes, adapter
params) beside `runs/<leg>-v1.json`. Phase 4 cost model reads the former;
the comparator reads the latter.

## Rationale correction (2026-09-28)

The original motivation ("escape the 60-min Gradle test wall") was a
misdiagnosis, corrected by evidence: every wall hit was tool-side (session
teardown killing nohup'd runs) or heap-side (512m worker OOM surfacing as
silent SKIP) — never a Gradle task timeout. The CLI stands on its independent
merits (absolute --out, build.json + checkpoint sidecars, --engine
unification, truncate/skip-resume, setsid detach), not on timeout avoidance.
Do not re-quote the wall as fact.

## Pre-run verification (mount gate)

Before any documented run, the harness (or operator) must run
`./scripts/verify-mounts.sh <container> <data-dir> [<certs-dir>]` and abort
on failure. A check operators must remember is the original 32s failure mode;
wire it into RunLeg startup (or the CI first step), never leave it manual.
Defaults resolve under `docker/data/`; first `up` creates them.

## Order

Sequential, one leg at a time: 024a → 024b → 028e (fixture only) →
baseline (028b). Lockfile forbids parallel runs.
