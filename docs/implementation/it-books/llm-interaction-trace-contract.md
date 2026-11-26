# LLM interaction trace contract for ontology-grounded extraction

**Status:** Proposed, documentation-only
**Scope:** IT Books Gateway extraction path
**Baseline:** #919 is historical evidence; this document does not change it
**Implementation:** none; no instrumentation, GPU call, or evaluation was run

## 1. Purpose

This contract defines an auditable, tenant-scoped record of one LLM interaction from the
pinned ontology release through the final evidence-bound extraction result. It deliberately
separates three things that are easy to conflate:

1. **The extraction contract** — what the ontology permits the model to emit.
2. **The prompt input** — what the model was actually shown, including any retrieved context.
3. **The model response** — the original termination metadata and generated text, followed by
   deterministic parsing/validation.

P0 contract hydration does **not** implement ontology concept retrieval. A hydrated contract
only supplies allowed types, predicates, endpoint expansions, and identity. It does not select
definitions, aliases, concepts, or graph context for a particular chunk.

## 2. Interaction identity

An interaction record is immutable and content-addressed.

| Field | Requirement | Notes |
|---|---|---|
| `interactionSchemaVersion` | required | e.g. `synanton.llm-interaction/v1` |
| `interactionId` | required | UUID/ULID for this request/response attempt |
| `tenantId` | required | never infer or substitute |
| `documentId` | required when available | do not merge with source version |
| `sourceVersionId` | required when available | pinned source identity |
| `chunkId` | required for chunked extraction | structural chunk identity |
| `extractionAttemptId` | required | attempt, not retry count alone |
| `gatewayRequestId` | required for Gateway calls | preserve verbatim; null only if genuinely absent |
| `gatewayExecutionId` | required for Gateway calls | preserve verbatim; never synthesize |
| `requestedAt` | required | UTC timestamp from platform request |
| `completedAt` | required | UTC timestamp after response/parse |

`gatewayRequestId` and `gatewayExecutionId` are correlation keys, not substitutes for the
interaction ID. A null value must be recorded as unavailable, never reconstructed.

## 3. Lifecycle trace

The following stages must remain distinguishable in the record even when several are implemented
by one class.

| Stage | Current source | Input | Output | Identity added | Important losses / failure behavior |
|---|---|---|---|---|---|
| 1. Pinned ontology release | `docs/implementation/it-books/p0/*`; P0 report/manifests | published ontology release artifacts | P0 release metadata | ontology ID, release ID/digest, compiler/schema versions | No runtime ontology DB read; definitions/aliases are not loaded |
| 2. P0 compiled contract | `P0ContractLoader`, `CompiledExtractionContract` | SPEC, template, report | allowed entity IDs, relation IDs, endpoint expansions, digests | SPEC/template/compilation digests, `contractDigest` | Report digests are checked for shape but not compared to artifact bytes; no concept retrieval |
| 3. Ontology context selection | **not implemented** | document/chunk + contract + ontology | (none) | none | No definitions, aliases, concept candidates, graph context, or CSO |
| 4. Prompt construction | `LlmExtractionAdapter.buildPrompt` | template v1/v2 + contract/context + chunk text | system prompt + user TEXT message | prompt version, compiled/rendered prompt digest | Relation labels/descriptions are not rendered; no token-budget selection |
| 5. Gateway SYNTHESIZE | `GpuPlaneSynthesisClient` via `synanton.gpu.v1/SYNTHESIZE` | completion request | generated text, `finish_reason`, request/execution IDs | Gateway execution identity | Full Gateway envelope is not retained by the current adapter |
| 6. Raw response | `CompletionResponse` / capture store | Gateway response | raw generated text and usage | content digest, finish reason | Successful responses are not captured; only parse failures/quarantines are |
| 7. Parsing | `JsonlLineParser` | generated text | accepted/rejected JSONL lines | parser outcome, rejection reasons | Legacy fallback can obscure the intended format; malformed output is not truncation |
| 8. Contract validation | `LlmExtractionAdapter` | parsed records | candidates + quarantines | unsupported type/predicate/domain-range reasons | `UNKNOWN` can be introduced by endpoint resolution; defaults may hide omissions |
| 9. P3a evidence binding | `EvidenceBinder`, `GpuSemanticEvaluation` | candidates + structural document | RESOLVED/UNMAPPED/MISBOUND | template/compilation/mapping digests | Binding success does not prove semantic correctness |
| 10. Extraction result | `ExtractionResult` / evaluation results | accepted candidates + evidence | predicted mentions/relations + metrics | per-chunk counts, metrics | Current evaluation results do not serialize per-attempt provenance |

### 3.1 Contract-versus-retrieval boundary

The **compiled contract** is a closed vocabulary and endpoint constraint set. It is not a
knowledge context. At present the model receives:

- 11 allowed entity type IDs;
- 8 allowed predicate IDs;
- 74 expanded subject/predicate/object constraint lines;
- release/compiler/schema identity and `contractDigest`;
- the chunk text in the user message;
- no canonical descriptions, aliases, examples, concept candidates, or graph neighborhood.

The prompt's `{allowed_types}` substitution uses IDs such as `DataStructure`, not labels such
as `Data Structure`; relation labels such as `authored by` are also absent. This is a vocabulary
contract, not ontology explanation.

## 4. Request record

The request artifact must contain or immutably reference all of the following.

```json
{
  "interactionId": "uuid",
  "tenantId": "tenant",
  "documentId": "document",
  "sourceVersionId": "source-version",
  "chunkId": "chunk",
  "extractionAttemptId": "attempt",
  "gateway": {
    "requestId": "gateway-request",
    "executionId": "gateway-execution",
    "contract": "synanton.gpu.v1/SYNTHESIZE"
  },
  "ontology": {
    "ontologyId": "it-books",
    "releaseId": "47fe38f8-9f72-481b-826a-6c5b12a6027a",
    "releaseDigest": "3a4a5edf876792ac92fa63494bd7181e4d8eb3826fb70e6fe4112d796be3831c",
    "compilerVersion": "p0-1",
    "schemaVersion": "1",
    "exportProfile": "it-books-v1-default",
    "p0SpecDigest": "8834d45a276792babbdf59ff6da43bf11ba5a8bdd43ca9755cfb159559694455",
    "p0TemplateDigest": "333a900292eebabda75b4ed67ec909ecc5db450702e05ae23d108ed2cf2fb37b",
    "p0CompilationId": "cea21d1a4cebf9e45e2e6ebba9ae4c3899dfc336b2b098a959db6dd5a06f7e0b",
    "contractDigest": "sha256"
  },
  "prompt": {
    "templateVersion": "extraction-v2",
    "templateDigest": "sha256",
    "compiledPromptDigest": "sha256",
    "renderedPromptDigest": "sha256",
    "systemMessage": {"inline": "...", "artifactRef": "..."},
    "userMessage": {"inline": "...", "artifactRef": "..."},
    "tokenEstimate": 0
  },
  "suppliedContract": {
    "entityTypes": ["Book", "Author"],
    "predicates": ["AUTHORED_BY"],
    "domainsAndRanges": [
      {"predicate": "AUTHORED_BY", "subjects": ["Book"], "objects": ["Author"]}
    ]
  },
  "retrievedContext": {
    "strategy": "none",
    "artifactRef": null,
    "concepts": [],
    "definitions": [],
    "aliases": [],
    "graphFacts": [],
    "cso": {"used": false, "artifactRef": null}
  },
  "model": {
    "identity": "synanton-qwen3-4b-synthesis",
    "deploymentRevision": "pinned-or-null",
    "temperature": 0.0,
    "maxTokens": 4096
  },
  "requestedAt": "2026-11-26T00:00:00Z"
}
```

Large message content should be stored as an immutable artifact with a digest, not inline in
Loki or another high-cardinality log stream. The record must preserve whether content was
available at run time; missing historical content must not be regenerated.

## 5. Response record

```json
{
  "interactionId": "uuid",
  "gateway": {
    "requestId": "gateway-request",
    "executionId": "gateway-execution",
    "finishReason": "stop"
  },
  "model": {"identity": "synanton-qwen3-4b-synthesis"},
  "rawResponse": {
    "artifactRef": "immutable-object-ref",
    "sha256": "sha256",
    "chars": 0,
    "note": "generated text and/or full Gateway envelope as captured"
  },
  "usage": {
    "promptTokens": 0,
    "completionTokens": 0,
    "totalTokens": 0,
    "latencyMs": 0
  },
  "parse": {
    "format": "JSONL",
    "acceptedLines": 0,
    "rejectedLines": 0,
    "legacyFallback": false,
    "malformed": false,
    "truncated": false
  },
  "validation": {
    "unsupportedEntityTypes": [],
    "unsupportedPredicates": [],
    "domainRangeViolations": [],
    "quarantined": []
  },
  "evidenceBinding": {
    "contractVersion": "p3a-evidence-binding-v1",
    "resolved": 0,
    "unmapped": 0,
    "misbound": 0
  },
  "outcome": "accepted | partial | quarantined | failed"
}
```

`finish_reason` and usage must come from the Gateway response. A parsed object must never be
presented as the original model output. The current Gateway adapter stores only
`CompletionResponse.text` in its raw body; that is not the full Gateway envelope.

## 6. Artifact and digest model

Recommended immutable artifacts:

| Artifact | Content | Digest |
|---|---|---|
| `request-prompt` | exact system/user messages and contract/context block | SHA-256 over canonical bytes |
| `gateway-response` | full safe response envelope or generated text with explicit scope | SHA-256 |
| `parsed-output` | parsed lines, never a substitute for raw response | SHA-256 |
| `quarantine` | rejected line + reason + safe raw fragment | SHA-256 |
| `evidence-result` | P3a candidate/result and structural identities | SHA-256 |

Rules:

- A digest proves byte identity; it does not prove that the content was sent.
- `contractDigest` currently covers canonical SPEC + template. It does **not** cover the prompt
  template text, model, chunk, retrieval context, decoding parameters, or evaluation profile.
- Record a separate `promptInputDigest` over all prompt-affecting inputs, or document exactly
  which subset each digest covers.
- Do not put high-cardinality IDs (`requestId`, `interactionId`, `chunkId`) in Loki labels;
  keep them in structured metadata/body and link to the artifact store.

## 7. Security, isolation, and retention

Raw prompts/responses may contain source text, tenant identifiers, and model-generated content.
They require an approved design before production storage:

- separate protected artifact store, not Loki;
- encryption at rest and in transit;
- tenant-scoped authorization and access audit;
- no secrets, bearer tokens, authorization headers, or private keys;
- no unrelated tenant data;
- explicit retention and deletion policy;
- export/access audit;
- content minimization/redaction where feasible;
- restore and key-rotation procedures.

The current `FileSystemCaptureStore` is diagnostic/test infrastructure. It is process-wide,
disabled by default, and has no encryption, KMS, access audit, or production enablement.
`GpuSemanticEvaluation` enables it for parse-failure/quarantine evidence only.

## 8. Current observability gap

| Capability | Current state |
|---|---|
| Successful raw prompts | not persisted |
| Successful raw Gateway responses | not persisted |
| Full Gateway envelope | not retained; adapter keeps generated text |
| `requestId` / `executionId` in result artifacts | returned in memory but not serialized by #919 results |
| Per-attempt ID | absent |
| Prompt digest in #919 manifest | absent |
| Contract digest in #919 manifest | absent (baseline predates #923 hydration) |
| Current evaluation hydration | `GpuSemanticEvaluation` loads the contract for its manifest but constructs the adapter without it; E0 is not yet wired |
| Structured retrieval context | absent; no retrieval implementation |
| Quarantine/parse-failure capture | present as filesystem diagnostic capture |
| Production artifact store/ACL/retention | absent |

The #919 baseline includes two tracked parse-failure captures and no successful interaction
artifacts. The post-#923 evaluation harness also has a wiring gap: it loads the compiled
contract but does not pass it to the adapter. This gap is real; it is not filled by
reconstructing prompts after the fact.

## 9. Compatibility

This model is additive to the existing Gateway/extraction contracts:

- no parallel LLM client;
- no bypass of `synanton.gpu.v1`;
- no replacement for #922 provenance fields;
- no change to #919 metrics, A2, IT Books v1, or CSO;
- no graph promotion or canonical assignment.

Implementation is deferred until security, retention, and access design are approved.
