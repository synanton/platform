# P0-3 audit — request-sourced inputs vs effective security context (2026-09-26)

Rule: no request input may broaden or bypass the validated `SecurityContext`.
Effective tenant is always the context tenant (`EligibilityScope`, P0-3 fix);
anything else throws `FORBIDDEN`.

| Input | Verdict | Basis |
|---|---|---|
| `EligibilityConstraints.tenantScope` | Enforced — mismatch rejected, incl. service contexts | `EligibilityScope` + `serviceContextBroadeningIsRejected` on all 3 engines |
| `EligibilityConstraints.principals` | Identity/audit only, never scope; cross-principal auth deferred | Code inspection (never read for scoping) + 025b `@Disabled` gap test |
| `EligibilityConstraints.policy` | Opaque reference, never interpreted | Code inspection; evaluation is 025b |
| `EligibilityConstraints.requireExplicitAuthorization` | Recorded, unenforced — no unproven-eligibility case exists while tenant scope is boundary-validated; enforcement lands with 025b principal/policy evaluation | Explicit deferral, not silent (see 025b) |
| `SearchRequest.embeddingModelRef` | Provenance only, no scope effect | Code inspection |
| `SearchRequest.filters` (+ `ChunkQuery` metadata) | Narrow-only within the eligible set, applied after eligibility in all adapters | Code inspection (in-mem, cassandra, ydb filter post-eligibility) |
| `SearchRequest.temporal` | Safe by rejection while unsupported; **re-audit mandatory** when any adapter supports temporal | Contract rejection tests; 025b-adjacent follow-up on support |

Synvault paths (`getDocument`, `getChunks`, `putDocument*`, `getProvenance`) take
no request-sourced scope at all — context-only by signature. Same guarantee,
stronger form; no changes needed.
