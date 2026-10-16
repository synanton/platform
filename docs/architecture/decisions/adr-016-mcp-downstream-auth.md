# ADR-016: MCP Downstream Auth — Service Identity, Not Token Exchange

**Status:** Proposed 2026-10-16
**Basis:** Blocker 2a (Q3-HIGH passthrough) requires deciding what MCP forwards downstream in place of the caller's raw bearer. Fact-find F1 resolved: no downstream service enforces authorization on the forwarded credential — zero Authorization reads across synapt, relix, and syntology main sources. The forwarded bearer was pure confused-deputy surface with no enforcement value. A second finding, tenant header misalignment, is live independently and is scoped separately as 2e.

## Decision

1. Downstream credential = service identity (option A), not token exchange (option B). B's case was "preserve downstream validation" — there is none to preserve. Token exchange (RFC 8693) is not implemented and is not needed for 2a.
2. Interface target = option C — ToolHandler.invoke(arguments, tenantId). The authHeader parameter is deleted. All 9 handlers updated; 3 that forwarded the bearer (Search, GraphQuery, OntologyResolve) drop the outbound Authorization header; the other 6 are unchanged.
3. A shared downstream client owns the credential — one place issues/attaches service identity, not per-handler.
4. F2 pending — the exact mechanism (mTLS, signed assertion, or "none" on a firm network trust-boundary answer) is deferred. ADR covers the A-vs-B decision; mechanism is a follow-up under 2a.
5. 2e is separate — tenant propagation is scoped and shipped independently of 2a. Bundling would delay the tenant fix behind F2.

Consequences:
- Blast radius recharacterized: L91's fail-open was uncontained, not partially contained. No downstream authorization layer stood behind MCP; a forwarding handler executed the full path. The "smaller-than-feared" reading was wrong and should not enter incident analysis.
- Single validation point: with A, MCP's filter is the sole authorization gate. Given blocker 1 was a fail-open in that filter, compensating controls (monitoring, alerting on denial rates) matter more than they would under B. Accepted, tracked.
- Blocker 6 unaffected in scope but shares input: F3 (token-exchange availability) is no longer a 2a question; it remains live for the external auth-boundary item.
- F2 load-bearing: A removes the credential that was (incorrectly) presented downstream. The replacement must be mTLS or a signed assertion; "none" only on a firm trust-boundary confirmation.
- Rejected: B — no downstream enforcement to preserve; adds external dependency (security service) for no benefit at this scope.
- Neutral: no change to internal service-to-service contracts; X-Tenant-Id header treatment is 2e's concern.

## Context

The passthrough was originally read as a confused-deputy with a compensating downstream check. That reading drove the initial preference for B (exchange the token, keep the downstream check working). F1 overturned it: the check does not exist. Once there is no downstream enforcement, A is strictly simpler and B has no case. The interface change (C) is the natural shape under either option and is taken now to avoid two rounds on the same auth path. F2's answer determines the mechanism only, not the direction.
