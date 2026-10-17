# ADR-019: Downstream Trust Predicate — Signed Short-Lived Context

**Status:** Proposed 2026-10-16
**Basis:** Propagation correctness landed — downstream reads tenant only from verified context, no fallback. But nothing proves the *sender* is trusted. An attacker who can reach synapt/relix directly can forward any envelope. F2 selects the mechanism that closes this. Security-provider already issues signed short-lived contexts and ships consumer middleware (`pkg/middleware`); mTLS infrastructure does not exist in the slice.

## Decision

1. **Slice mechanism: signed short-lived context.** Security-provider issues an HMAC-signed envelope after authenticating the user; MCP forwards it downstream; synapt/relix verify signature, expiry, issuer, and audience before trusting any claim. Uses infrastructure that exists.
2. **Target state: mTLS additionally.** Signed context proves the *context* is authentic; mTLS proves the *sender* is who it claims. Both are wanted. Signed context ships first because it requires no mesh, no cert provisioning, and no per-service key distribution beyond the security-provider's existing key.
3. **Fail-closed on tenant-scoped routes only.** Exempt routes (`/health`, `/readyz`, `/livez`, `/metrics`, enumerated Actuator endpoints) do not require a tenant envelope. Exemption removes the envelope requirement only — any authentication or authorization the route already performs remains in force. `GET /mcp` capabilities exemption is owned by the MCP filter, not the shared classifier; business requests to `/mcp` are envelope-required. No default tenant, no fallback.
4. **Cryptographic verification precedes claim validation.** The envelope's own `iss`, `aud`, `exp`, and `tenant_id` are untrusted until the signature verifies. Verification order: signature → issuer → audience → expiry/issued-at → tenant/subject presence. No unverified envelope claim may be bound to request context or exposed to application code.
5. **Staged delivery.** Wave 1 (this ADR's PR): route classification, `EnvelopeVerifier` contract, `VerifiedSecurityContext`, `InvalidSecurityContextException`, and a fail-closed placeholder. No production filter is restructured, and the placeholder is not wired. Wave 2 (follow-up, after the security-provider envelope wire format is specified): real verifier, filter integration, negative verification tests, and integration tests proving exempt endpoints are registered and reachable.

Consequences:

- **Security-provider:** envelope format and signing scheme become a consumer contract. Needs a spec document.
- **MCP:** forwards the envelope it receives from the security-provider validation call, unchanged. No new signing key on MCP.
- **synapt/relix:** add envelope verification before any tenant read. Prefer `pkg/middleware` (Go) where available; HTTP verification via `/v1/security-context/validate` otherwise. The verification *order* is: channel/transport → envelope signature → expiry → audience → tenant extraction.
- **Health checks and operational endpoints:** explicitly exempt, explicitly tested. The exemption list is code, not convention.
- **HMAC key lifecycle is a security design concern, not an implementation detail.** Key distribution, rotation, and per-message key selection must be specified alongside the envelope format. PR 241's `ValidatedIdentityResponse` carries a `keyId` field; whether the signed envelope carries one, and whether it selects the verification key, is a wire-spec question for the security-provider — not assumed here.
- **Rejected:** mTLS-only for the slice — requires mesh infrastructure not present. Both mechanisms eventually; signed context is the slice.
- **Neutral:** ADR-016's service identity decision stands; this adds the channel-independent proof that complements it.

## Context

Propagation correctness eliminated the wrong-tenant behavior. It did not eliminate the forged-tenant-header path — a direct caller to synapt with a crafted envelope passes the propagation filter. The security-provider's model (validate → issue signed context → consumer verifies) already answers this; the work is wiring consumers to use it and specifying the envelope.
