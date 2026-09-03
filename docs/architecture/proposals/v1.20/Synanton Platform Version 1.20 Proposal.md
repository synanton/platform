# Revised Proposal: Synanton Platform v1.20 

**Version:** 1.1
**Date:** 2026-07-21
**Status:** Draft for review
**Related docs:**  `synanton-design-1.19.md`

------

## Executive Summary

Version 1.19 is a **strictly additive** release that introduces two new operational CLI modules (`helper` and `wizard`), a new RBAC role (`support_admin`), and a set of internal admin API endpoints on `synapt` and `control-plane`. The changes are well-scoped, non‑breaking, and address a clear  operational gap: providing a safe, auditable, and offline‑capable way to perform day‑2 support tasks and deployment generation without exposing  live‑cluster credentials.

The design remains consistent with the platform’s core principles-**unified identity**, **bidirectional failure isolation**, **cost as a first‑class signal**, and **honest capability surfacing**. The additions are thoroughly specified, with metrics, alerts, audit  trails, and a clear compatibility statement. No existing contracts,  schemas, or module identities are altered.

------

## Overview of v1.19 Changes

| #    | Change                                                       | Home           | Category                             |
| ---- | ------------------------------------------------------------ | -------------- | ------------------------------------ |
| 1    | New `helper` module – operational day‑2 CLI that executes support tasks exclusively via the platform’s internal admin API (authenticated as `support_admin`) | §26b           | Additive (new binary)                |
| 2    | New `wizard` module – offline deployment‑artifact generator (Terraform / K8s Helm / Docker Compose / `.env`) requiring zero live‑cluster credentials | §26c           | Additive (new binary)                |
| 3    | New `support_admin` RBAC role – reserved for automated support tooling and break‑glass accounts; routes all helper actions through `admin_audit` | §26 (security) | Additive (new role)                  |
| 4    | New internal admin API endpoints (`/admin/_internal/*`) on `synapt` and `control-plane` | §24, §27       | Additive (new routes)                |
| 5    | `SYNANTON_SUPPORT_KEY` credential lifecycle – argon2id hashing, 90‑day (STANDARD) / 30‑day (HIGH_SECURITY) rotation | §26a           | Additive (extends API key lifecycle) |
| 6    | Phase plan updated – `helper` and `wizard` appear in all five phases | §48            | Additive (planning)                  |
| 7    | New metric `helper_operation_total` and associated audit table wiring | §45            | Additive (observability)             |

All changes are **opt‑in**: the new modules are delivered as a single Go binary (`synanton-ops`) wrapped by `synctl`, and the new internal API endpoints are unreachable without the `support_admin` role. The default configuration preserves v1.18 behaviour.

------

## Detailed Assessment

### 1. Architectural Fit

The `helper` and `wizard` modules fill a clear gap in the operational model:

- **Day‑2 support tasks** (status checks, bundle collection, cleanup, recrawl, workflow  cancellation/retry) previously required either direct access to storage  (risky) or ad‑hoc scripts. The `helper` enforces a **single admin API surface**, making all actions auditable and idempotent.
- **Deployment setup** is often a stumbling block for new environments. The `wizard` generates infrastructure-as-code from a single configuration file,  eliminating the need for live credentials and reducing human error  during onboarding.

Both modules are **well‑isolated**:

- `helper` never touches storage directly – it only calls internal admin APIs.
- `wizard` is a pure code generator with no runtime dependency on the cluster.

This aligns with the principle of **minimum necessary privilege** and **honest capability surfacing** – the platform exposes exactly what these tools can do, and no more.

### 2. Security & Identity

The introduction of `support_admin` is a prudent separation of concerns:

- Human admins (with `admin` role) retain full control.
- Automated support tools and break‑glass accounts use a **restricted role** that can only invoke the internal admin API endpoints.
- Every helper action is logged in `admin_audit` with `before_state_hash` and `after_state_hash`, enabling **change‑tracking and replay**.

The **API key lifecycle** for `SYNANTON_SUPPORT_KEY` is appropriately hardened:

- Argon2id hashing with m=64MB, t=3 – resistant to offline brute‑force.
- Rotation periods (90d/30d) match the security tier of the tenant.
- Grace periods and notifications allow overlap without service interruption.

The only potential concern is that the `support_admin` role is not assignable to human users through normal IdP flows – this  is stated, but the mechanism for "break‑glass accounts" could be  elaborated (e.g., time‑limited, requires secondary approval). However,  this is likely covered by operational runbooks outside the design  document.

### 3. Internal Admin API Design

The set of endpoints is pragmatic:

- `GET /admin/_internal/status` – health/readiness checks for support.
- `POST /admin/_internal/bundle` – collect diagnostic bundle (logs, metrics, traces).
- `POST /admin/_internal/clean` – clean caches or temporary resources.
- `POST /admin/_internal/delete` – delete a specific resource (with appropriate guards).
- `POST /admin/_internal/recrawl` / `GET …/recrawl/{tenant}` – trigger and monitor recrawl.
- `POST /admin/_internal/workflow/cancel` / `retry` – manage Temporal workflows.

The endpoints are **internal‑only** (not part of the public API), and they require `support_admin` service principals. This avoids accidental exposure to regular users.

One minor gap: the specification doesn’t mention **rate limiting** or **concurrency limits** on these internal endpoints. While `helper` itself is a CLI (typically single‑threaded), a misconfigured or compromised `support_admin` credential could invoke these endpoints at scale. It would be prudent  to add per‑key or global throttling for internal admin endpoints in a  future version.

### 4. Observability & Alerts

The new metric `helper_operation_total{command, tenant, outcome}` is a welcome addition. It allows SREs to monitor support activity and  detect anomalies (e.g., frequent clean operations or recrawl triggers).

Two new alerts are defined:

- `HelperDestructiveOpsRate` – fires when destructive operations (delete, clean) exceed a baseline, potentially indicating abuse or automation gone wrong.
- `HelperAuthFailureSpike` – alerts on repeated authentication failures for `support_admin`, which could signal a credential leak or brute‑force attempt.

These alerts are well‑chosen and complement the existing security monitoring.

### 5. Compatibility & Upgrade

The compatibility statement is clear and comprehensive:

- No breaking changes.
- New modules are **opt‑in** – the binary is distributed but not automatically invoked.
- Rolling upgrades from v1.18 are safe – existing modules only gain new internal routes that are unreachable without the new role.
- All new config keys have safe defaults.

This upholds the **clean‑slate** and **zero‑legacy** philosophy – new features are layered without creating tech debt.

------

## Potential Concerns & Recommendations

### 1. `support_admin` Role Assignment

- **Concern:** The design states that `support_admin` is not assignable to human users through normal IdP flows. However, it  doesn’t specify how a break‑glass account is created or how its access  is time‑limited.
- **Recommendation:** Add a brief section describing the break‑glass procedure (e.g.,  requires two‑person approval, limited TTL, automatically revoked after  use). This could be in the operational runbooks rather than the design  document, but a reference would be helpful.

### 2. Internal API Throttling

- **Concern:** No explicit throttling or concurrency limits on the `/admin/_internal/*` endpoints.
- **Recommendation:** Add configurable rate limits (e.g., `synapt.admin.internal.rate_limit_requests_per_minute`) and global concurrency caps to prevent resource exhaustion if a credential is abused.

### 3. `wizard` Artifact Security

- **Concern:** The `wizard` generates Terraform/K8s manifests and `.env` files. These may contain secrets (e.g., database passwords, API keys). The design states it requires **zero live‑cluster credentials**, but the generated artifacts might still include hard‑coded credentials (if the configuration file contains them).
- **Recommendation:** Explicitly recommend that the generated artifacts use **placeholders** or **external secrets** (e.g., Vault) rather than embedded secrets. The `wizard` should never write secrets into the generated files; it should emit warnings if sensitive values are detected.

### 4. `helper` idempotency

- **Concern:** Some `helper` commands (e.g., `clean`, `delete`, `recrawl`) may not be fully idempotent. The design relies on the underlying  internal endpoints to be idempotent, but that’s not guaranteed for all  operations.
- **Recommendation:** Add a requirement that all internal admin endpoints **must** be idempotent or explicitly document the expected behaviour (e.g., `delete` returns 200 if resource already gone). This is implied but could be stated.

### 5. Dependency on `synctl`

- The `helper` and `wizard` are delivered as a single Go binary wrapped by `synctl`. This is consistent with the principle of a single CLI, but the document does not detail the distribution mechanism (e.g., how `synctl` fetches the binary). Appendix D covers build & distribution – it’s brief but sufficient for now.

------

## Overall Assessment

The v1.19 design is **well‑executed, focused, and safe**. It addresses real operational pain points without compromising the  platform’s stability or security posture. The additive nature ensures  that existing installations can upgrade without risk, and the new  modules provide immediate value for SREs and platform engineers.

The integration of `helper` with the existing audit and RBAC systems is particularly strong – every support action leaves a trace, and the `support_admin` role provides a clear boundary between support tools and human admins.

**Minor recommendations** (throttling, break‑glass details, wizard secret handling) are  non‑blocking and can be addressed in follow‑up revisions or operational  documentation.