# Gate 1 image-delivery preflight

**Status:** infrastructure proposal; no benchmark execution.
**Approved target:** node3, `GPU-2270a494...`, Gate 1 performance packet.
**Approved image:** `local-registry:5000/vllm-openai:v0.29.0`
**Approved digest:** `sha256:082ca6f035279109041ffd3fe0695cb568b29bc580b35c4f297a66a08b216c1b`
**Delivery mode:** digest-pinned image already present in the running node3
container runtime, with `imagePullPolicy: IfNotPresent`.

## Why this avoids the failed pull

The failed Gate 1 baseline Pod used a tag reference in a namespace with no
`imagePullSecret`; kubelet attempted a registry pull and entered
`ImagePullBackOff`. The running production vLLM pod on node3 already proves the
same image digest is present in the node runtime. Gate 1 should reference the
digest directly and must not copy `local-registry-cred` or any production pull
secret into the benchmark namespace.

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
- the production reference is not digest-pinned;
- the image does not come from the approved registry repository.

Only a `PASS` result authorizes using the digest reference for a Gate 1 Pod.
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
