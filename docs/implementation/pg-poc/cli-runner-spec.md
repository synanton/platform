# CLI.1 — Documented-Run Runner Spec

Smallest change that fixes the 60-min wall: documented runs leave the test
worker (plain `java -cp`, no Gradle), keeping every gate the test had.

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

## Order

Sequential, one leg at a time: 024a → 024b → 028e (fixture only) →
baseline (028b). Lockfile forbids parallel runs.
