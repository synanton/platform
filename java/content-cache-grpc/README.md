# content-cache-grpc

gRPC `ContentStore` client for the Content Service
(`content.v1.ContentService`).

- `GrpcContentStore` implements `org.synanton.contentcache.ContentStore`
  over generated stubs: explicit RPC deadlines (default 5s), tenant
  scoping per call, digest verification on read (fail-closed),
  `NOT_FOUND`/`UNAVAILABLE` mapping. Plaintext factory owns its channel;
  channel injection is available for tests and DI.
- The `.proto` file mirrors content-service
  `proto/content/v1/content.proto` (wire contract owned by Content Cache
  1.26); only additive `java_*` options differ.
- Lifecycle operations beyond put/get/exists (reference register and
  release, legal hold, retention) have no RPC surface yet, so they fail
  explicitly rather than silently no-op. They unblock with the Reference
  Registry API (proposed, not implemented).
- Tests run against an in-process fake delegating to
  `InMemoryContentStore`: roundtrip, dedup, cross-tenant isolation,
  tamper detection, validation, lifecycle failure modes. No Docker.
