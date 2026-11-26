# Platform Content Extractor Dependency

The platform treats the existing Structured Content Extraction Plane (SCEP) as a
required dependency for structured ingestion, not as an optional enrichment.

## Endpoints

- Client gRPC: `extraction-gateway:9091`
- Readiness HTTP: `http://<service>:8092/actuator/health`, expecting
  `{"status":"UP"}`
- Kubernetes local-real-demo owns the `extraction-gateway` Deployment.
- Compose owns the `extraction-gateway` service.

The gRPC endpoint and HTTP health endpoint are different contracts. A healthy
HTTP response does not prove that a submission returns a usable structured
payload; the ingestion stage therefore validates the payload independently.

## Strict extraction

`EXTRACTION_CLIENT_ENABLED=true` and
`EXTRACTION_FALLBACK=structured-required` are explicit in both local-real-demo
manifests. In strict mode the following fail the item instead of using local
Tika:

- client disabled;
- service unavailable;
- unsuccessful operation status;
- missing inline payload;
- malformed or empty structural payload;
- structured chunking producing no chunks.

`local-tika` remains available only when the fallback policy is explicitly set
to `local-tika`.

## Lifecycle ownership

`start.sh` gates extraction-dependent workloads on rollout plus the HTTP health
response. `stop.sh` scales `extraction-gateway` only when
`EXTRACTION_GATEWAY_MANAGED=1` (the default for the platform-owned deployment).
For an externally managed extractor, set `EXTRACTION_GATEWAY_MANAGED=0` and
provide `EXTRACTION_GATEWAY_HEALTH_URL`; the script checks readiness but never
stops or restarts that service.

GPU Runtime device-plugin and VRAM checks remain in the GPU Runtime lifecycle
and are not part of the platform scripts.

## Source correspondence

The extraction contract has no source character offsets. This platform work
does not invent offsets from normalized or flattened text. Exact P1 evidence
span correspondence is not claimed until an explicit coordinate extension is
defined.
