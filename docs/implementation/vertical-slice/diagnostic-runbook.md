# Vertical slice — diagnostic runbook

**Status:** Active · **Last reviewed:** 2026-10-19

When the slice fails, check in this order. Each section names what the failure
looks like and what it usually is.

The order is by frequency of the disguised failure, not by component layer.
The twelve cluster-run findings each presented as a different class of failure
than their cause — this runbook is those findings organized as a procedure.

---

## 1 · Pod not Ready / crash loop

**Looks like:** pod never Ready, or CrashLoopBackOff.

**Check first:** is the readiness probe's path reachable, and is the readiness
state coupled to data initialization?

**Usually:** readiness path misconfigured, or a service's readiness depends on
a data layer that hasn't converged. `SearchService` was once stuck at
`STARTING` forever because readiness was coupled to tenant index initialization.

**Fix shape:** readiness must be independent of data-layer state. If a health
check needs a seed to pass, the seed is the bug.

---

## 2 · ImagePullBackOff on an image that exists

**Looks like:** the manifest references a valid image, but the pod can't pull it.

**Check first:** is the image tag in the deployment the one that was mirrored?

**Usually:** a placeholder tag survived into the applied manifest, or the image
was mirrored under a different tag than the deployment references.

**Fix shape:** `IMAGE_TAG` substitution should be verified before apply, not
after. A raw apply of a manifest with `PLACEHOLDER_TAG` produces ImagePullBackOff
for an image that exists.

---

## 3 · Container stuck in `ContainerCreating`

**Looks like:** pod scheduled, container never starts.

**Check first:** `kubectl describe pod` — the event usually names a volume.

**Usually:** a static local PV's path does not exist on the node the pod
scheduled to, or the PV's `nodeAffinity` does not match.

**Fix shape:** static PVs must be created per node with matching
`nodeAffinity`. `local` volumes with `WaitForFirstConsumer` bind at scheduling
time, not before.

---

## 4 · 401 on a route that should be authenticated

**Looks like:** the client presents valid credentials, gets 401.

**Check first:** is more than one tenant filter registered in the chain?

**Usually:** a legacy mock filter runs after the verified-identity filter at
default precedence and overwrites the context. Or the secret the container
reads is not the secret the test token was signed with.

**Fix shape:** `TenantFilterRegistrationTest` asserts only the expected filter
is registered. Secret-key wiring uses `secretKeyRef` per key, not `envFrom`.

---

## 5 · "Not healthy" mid-run

**Looks like:** the acceptance script reports a health check failing partway
through.

**Check first:** is the port-forward still alive?

**Usually:** the port-forward process died when the target pod restarted. The
script's `http()` retry treats connection-refused as retryable but does not
distinguish "port-forward gone" from "service not listening yet."

**Fix shape:** port-forwards should be supervised or the script should
re-establish them on connection failure, not just retry.

---

## 6 · Rollout timeout under required anti-affinity

**Looks like:** `kubectl rollout status` times out; old pods still running.

**Check first:** how many nodes can schedule the pod?

**Usually:** the deployment's `maxSurge` exceeds available scheduling slots
under `requiredDuringSchedulingIgnoredDuringExecution` anti-affinity. With two
eligible nodes and two replicas, `maxSurge: 1` cannot start the third pod
needed for the surge.

**Fix shape:** rollouts under required anti-affinity must set `maxSurge: 0`,
and settling must identify the current ReplicaSet by desired-replica count and
wait for old pods to be gone.

---

## 7 · Silent no-redundancy

**Looks like:** everything works; nothing fails.

**Check first:** `DESCRIBE KEYSPACE` — is the replication factor what the
cluster topology needs?

**Usually:** the service's schema migration ran `RF=1` before the pre-created
keyspace was applied. The service's migration cannot overwrite an existing
keyspace, but if it runs first, the pre-created one never wins.

**Fix shape:** pre-create the keyspace with the correct RF before the service
starts, or the migration needs to detect and assert RF.

---

## 8 · Fault injection crash-loops instead of recovering

**Looks like:** the fault-injected worker restarts and immediately crashes again.

**Check first:** is the fault-trigger environment variable still set in the
deployment spec?

**Usually:** yes. Kubernetes recreates pods from the deployment spec on every
crash; the env var persists across restarts.

**Fix shape:** scale to zero first (stop restarts), then clear the env var, then
scale up. The `fault-inject.sh` script implements this ordering; a manual
`kubectl delete pod` will crash-loop.

---

## What is not in this runbook

Failures that present as themselves — a genuine network partition, a genuine
disk-full condition, a genuine image that does not exist. Those are diagnosed
by their own symptoms.

This runbook exists for failures that present as a different class than their
cause.
