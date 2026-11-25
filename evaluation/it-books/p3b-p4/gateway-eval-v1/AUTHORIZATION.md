# Full semantic P3b/P4 evaluation authorization

**Authorized operation:** `it-books-gateway-evaluation-v1`
**Provider path:** `synanton.gpu.v1` `SYNTHESIZE` via `GpuPlaneSynthesisClient`
**Input:** A2 `it-books-a2-v1`
**Structural input:** P1 `STRUCTURAL_CHUNK`
**Evidence:** `p3a-evidence-binding-v1`
**Tenant:** `it-books-eval`
**Model:** `synanton-qwen3-4b-synthesis` (local vLLM; provider LOCAL)

This record supersedes the regex-smoke-only authorization for this evaluation. It permits:

- Gateway-backed semantic extraction through `SYNTHESIZE`.
- Two pinned evaluation runs over A2.
- P3a evidence binding and A2-scored metrics.
- Read-only evaluation artifacts and manifests.

It does not permit:

- Direct vLLM/TEI HTTP calls from platform application code.
- Bypassing the GPU Gateway.
- Automatic provider fallback.
- Graph promotion or canonical entity assignment.
- Production/customer index writes.
- Mutation of A2 annotations or source fixtures.
- P2, Docling, or F1/gold/gate/profile/ADR-044 changes.
