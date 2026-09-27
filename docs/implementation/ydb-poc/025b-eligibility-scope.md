# YDB-POC-025b — Full eligibility scope decision (principal / policy / explicit-authz)

**Status:** Open — scope decision required (deferred = throwaway PoC keeps
tenant-only; implemented = conformance entry upgrades on green tests)
**Origin:** PR #51 P0-2 eligibility evidence-scope correction.

## Fact

`tenantEligibilityIsPreRanking()` proves tenant isolation, not the full
`EligibilityConstraints` contract. Principal, policy, and
`requireExplicitAuthorization` dimensions are unimplemented and untested on all
three adapters. Matrices record `synquest.eligibility` as
`PARTIAL(scope=tenant)` — honest as of this commit.

## Decision needed

- **Option A (defer, throwaway-consistent):** tenant-only stands; PARTIAL entry
  stays; the `@Disabled principalPolicyEligibilityIsEnforced` test stays
  disabled as the visible gap. No code.
- **Option B (implement):** enforce principal/policy/requireExplicitAuthorization
  in all three engines + YDB filtered predicates; flip the disabled test on;
  upgrade the entry to SUPPORTED only when green.

If deferred, the conformance entry must keep reflecting that decision (it does).
If implemented, the entry upgrades on evidence, never before.
