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

## Architectural invariant

Document extraction is an externalized platform capability provided by the
Synanton Content Extraction Plane. Platform must not embed, duplicate, or
silently substitute document extraction implementations. Tika, PDF, OCR, HTML,
office-document, and future providers belong behind the Content Extractor
contract; they are not a second platform extraction path.

## Required extraction

`EXTRACTION_CLIENT_ENABLED=true` is explicit in local-real-demo manifests. The
platform has no provider fallback. The following fail the item:

- client disabled;
- service unavailable;
- unsuccessful operation status;
- missing inline payload;
- malformed or empty structural payload;
- structured chunking producing no chunks.

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
