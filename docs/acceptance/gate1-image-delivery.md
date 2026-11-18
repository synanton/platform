# Gate 1 image-delivery preflight

**Status:** image pull validated with scoped credential; no-secret path rejected.
**Probe evidence:** `docs/acceptance/artifacts/fp8-gate1/image-cache-probe-2026-11-18T1609Z.txt`
— credential pull PASS on node3; no-secret `IfNotPresent` FAIL/ImagePullBackOff.
**Approved target:** node3, `GPU-2270a494...`, Gate 1 performance packet.
**Approved image:** `local-registry:5000/vllm-openai:v0.29.0`
**Approved digest:** `sha256:082ca6f035279109041ffd3fe0695cb568b29bc580b35c4f297a66a08b216c1b`
**Delivery mode:** digest-pinned image with an explicit `nodeName: node3`
scheduling pin and the dedicated scoped secret `gate1-registry-cred`.
`IfNotPresent` is retained, but the secret is present in case kubelet needs to
resolve/pull the image.

**Validated path:** a no-GPU probe on node3 pulled the exact digest with
`gate1-registry-cred`, completed with exit 0, and reported the approved image
ID. A follow-up no-secret probe failed; do not use the no-secret template for
Gate 1. Do not copy `local-registry-cred`.

## Scheduling guarantee

The Gate 1 Pod uses an explicit `nodeName: node3`, matching the GPU runtime
blueprint (`gpu-runtime/deployments/homelab/blueprints/vllm/extraction-llm.yaml`):

```yaml
spec:
  nodeName: node3
  imagePullSecrets:
    - name: gate1-registry-cred
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

The manifest must contain `nodeName: node3`, the exact digest,
`imagePullPolicy: IfNotPresent`, and exactly `imagePullSecrets: [gate1-registry-cred]`.
No other pull secret is permitted.

Only a `PASS` result after a no-GPU cache probe authorizes using the digest
reference for a Gate 1 Pod. Provenance checks alone are insufficient. Set
`CACHE_PROBE_VERIFIED=1` only with `CACHE_PROBE_EVIDENCE` pointing to a probe
record whose `Result:` line is `PASS`; the current probe evidence is `BLOCKED`.
The Pod must use:

```yaml
image: local-registry:5000/vllm-openai@sha256:082ca6f035279109041ffd3fe0695cb568b29bc580b35c4f297a66a08b216c1b
imagePullPolicy: IfNotPresent
imagePullSecrets:
  - name: gate1-registry-cred
```

## Scoped pull credential (required)

If the approved image is not present on the target node, a dedicated
least-privilege registry credential scoped to the required repository/image may
be provisioned in the benchmark namespace. It must be:

- owned by the production service owner;
- limited to read/pull of the approved image repository;
- short-lived, rotated, and deleted with the namespace;
- never a copy of `local-registry-cred` or another production pull secret.

Pre-pulling with an approved credential outside the namespace does not make the
no-secret path safe; the no-secret probe already failed. Gate 1 must reference
`gate1-registry-cred`.

## Ownership and cleanup

| Item | Requirement |
|---|---|
| Image provenance | Approved digest + successful credential pull |
| Namespace secret | `gate1-registry-cred`, scoped read-only |
| Fallback secret owner | Production service owner |
| Rotation/cleanup | Short TTL; delete with namespace; no production secret copy |
| Failure mode | Fail fast before Pod creation; no ImagePullBackOff retry loop |
| Rollback | No runtime change on preflight failure |

## Boundaries

This change does not allocate a GPU, create a benchmark Pod, run a benchmark, or
alter production. It is a prerequisite for a separately authorized Gate 1
retry.
