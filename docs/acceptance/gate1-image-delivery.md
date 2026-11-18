# Gate 1 image-delivery preflight

**Status:** BLOCKED — image cache/no-secret startup is not verified.
**Latest probe:** `docs/acceptance/artifacts/fp8-gate1/image-cache-probe-2026-11-18.txt`
— `ErrImagePull`, `no basic auth credentials`, on node3 with the exact digest.
**Approved target:** node3, `GPU-2270a494...`, Gate 1 performance packet.
**Approved image:** `local-registry:5000/vllm-openai:v0.29.0`
**Approved digest:** `sha256:082ca6f035279109041ffd3fe0695cb568b29bc580b35c4f297a66a08b216c1b`
**Intended delivery mode:** digest-pinned image with
`imagePullPolicy: IfNotPresent` and an explicit `nodeName: node3` scheduling
pin. This is not yet proven usable for a new Pod.

**Important correction:** the production Pod’s `imageID` proves only the
running container provenance. It does **not** prove that a new Pod can start
from node3’s image store. A no-GPU probe on node3 failed with
`ErrImagePull: no basic auth credentials`. Gate 1 remains blocked until the
exact digest is either scoped-credential pullable or pre-pulled and verified.

## Scheduling guarantee

The image cache is node-local. `IfNotPresent` is safe only when the Pod runs on
the node whose CRI cache contains the approved digest. The Gate 1 Pod therefore
uses the same explicit pin as the GPU runtime blueprint
(`gpu-runtime/deployments/homelab/blueprints/vllm/extraction-llm.yaml`):

```yaml
spec:
  nodeName: node3
  containers:
    - image: local-registry:5000/vllm-openai@sha256:082ca6f...
      imagePullPolicy: IfNotPresent
```

Do not rely on the scheduler, a Service, or a nodeSelector. Node3 is the only
node with the approved Blackwell target and the model artifact; scheduling must
be explicit. The template is
`docs/acceptance/artifacts/fp8-gate1/gate1-pod-template.yaml`.

## Why this avoids the failed pull

The failed Gate 1 baseline Pod used a tag reference in a namespace with no
`imagePullSecret`; kubelet attempted a registry pull and entered
`ImagePullBackOff`. Pinning to the digest and node3 is still required, but the
follow-up no-GPU probe showed the digest is not available to a new Pod without
registry credentials. Gate 1 must not copy `local-registry-cred` or any
production pull secret; it needs a dedicated scoped credential or approved
pre-pull.

## Mandatory preflight

Run:

```bash
EXPECTED_NODE=node3 \
EXPECTED_DIGEST=sha256:082ca6f035279109041ffd3fe0695cb568b29bc580b35c4f297a66a08b216c1b \
./scripts/preflight-gate1-image.sh
```

The script is read-only and fails with `BLOCKED` if:

- no running production vLLM pod exists on the expected node;
- the production image digest differs from the approved digest;
- the image does not come from the approved registry repository.

Run the manifest check before creating a Gate 1 Pod:

```bash
POD_MANIFEST=docs/acceptance/artifacts/fp8-gate1/gate1-pod-template.yaml \
EXPECTED_NODE=node3 \
EXPECTED_DIGEST=sha256:082ca6f035279109041ffd3fe0695cb568b29bc580b35c4f297a66a08b216c1b \
./scripts/preflight-gate1-image.sh
```

The manifest must contain `nodeName: node3`, the exact digest, and
`imagePullPolicy: IfNotPresent`; it must not contain `imagePullSecrets`.

Only a `PASS` result after a no-GPU cache probe authorizes using the digest
reference for a Gate 1 Pod. Provenance checks alone are insufficient. Set
`CACHE_PROBE_VERIFIED=1` only with `CACHE_PROBE_EVIDENCE` pointing to a probe
record whose `Result:` line is `PASS`; the current probe evidence is `BLOCKED`.
The Pod must use:

```yaml
image: local-registry:5000/vllm-openai@sha256:082ca6f035279109041ffd3fe0695cb568b29bc580b35c4f297a66a08b216c1b
imagePullPolicy: IfNotPresent
# no imagePullSecrets
```

## Fallback: scoped pull credential

If the approved image is not present on the target node, a dedicated
least-privilege registry credential scoped to the required repository/image may
be provisioned in the benchmark namespace. It must be:

- owned by the production service owner;
- limited to read/pull of the approved image repository;
- short-lived, rotated, and deleted with the namespace;
- never a copy of `local-registry-cred` or another production pull secret.

Pre-pulling with an approved credential outside the namespace is also
acceptable, followed by the same digest-pinned no-secret Pod.

## Ownership and cleanup

| Item | Requirement |
|---|---|
| Image provenance | Production vLLM digest on node3 + approved digest |
| Namespace secret | None for the approved path |
| Fallback secret owner | Production service owner |
| Rotation/cleanup | Short TTL; delete with namespace; no production secret copy |
| Failure mode | Fail fast before Pod creation; no ImagePullBackOff retry loop |
| Rollback | No runtime change on preflight failure |

## Boundaries

This change does not allocate a GPU, create a benchmark Pod, run a benchmark, or
alter production. It is a prerequisite for a separately authorized Gate 1
retry.
