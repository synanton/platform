# Fault-injection tests (P1 fixes from #293)

The P1 guarantees are proven at module level; these runs prove them under
real k8s restarts and cross-node scheduling. **Both synflux replicas must be
on different nodes** — the `deploy.sh` assertion enforces it, because a
co-located pair makes every test below a false positive.

| Test | Fault | Assertion |
|---|---|---|
| #289 lease-loss | `after-acquire` | worker exits 70; lease is taken over; **exactly one** SourceVersion |
| #291 manifest ordering | `after-manifest` | pre-restart: no version row, no run manifest; post-restart: exactly one of each |
| #292 set race | none — two concurrent same-bytes ingestions | both source versions in the chunk's `source_version_ids` set |

## Procedure
The acceptance script (`tests/integration/vertical-slice.sh`,
`WORKER_PROFILE=k8s`) drives the recovery sequence automatically and asserts
the pre/post state pairs. For a manual pass:
TOKEN=...                                # minted from the cluster JWT secret
curl -s -X POST "http://localhost:18090/v1/ingestion-runs" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"source_id":"<slice-recovery-id>","resource_id":"recovery-doc.md"}'

# 2. Inject the crash at the point under test
./scripts/fault-inject.sh after-manifest

# 3. Confirm the workflow resumed
curl -s "http://localhost:18090/v1/operations/<op-id>" -H "Authorization: Bearer $TOKEN"
#    → status SUCCEEDED, outcome FULL
```

## Why scale-0 → clear → scale-up
k8s restarts the crashed pod with the SAME Deployment spec — the fault env is
still set, and the one-shot-per-process guard resets on every new JVM. The
pod would crash-loop forever. The order is load-bearing: **scale to 0 first**
(stop restarts), **clear the env**, then **scale back up**. `fault-inject.sh`
implements it; the crash is detected by the terminated container's
`exitCode: 70` (durable across restarts — container logs are not).
