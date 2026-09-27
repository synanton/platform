# YDB-POC-009 — Cross-Tenant Relay Decision

**Status:** Closed (Phase 0A)
**Date:** 2026-09-26

## Decision

**Default: no cross-tenant relay.** `PublicationLog.pending()` is tenant-scoped by construction (§12.2); each tenant's relay consumes only its own scope with its own `SecurityContext`.

## Exception path (only if operations requires it)

If a deployment needs a single cross-tenant relay process, it must be approved as an explicit architectural exception with all three of:

1. a dedicated service-level `SecurityContext` (never an end-user context);
2. audit logging of every cross-tenant read it performs;
3. a written exception record referencing this file.

No such requirement exists at PoC scope — per-tenant relays are used throughout Phases 1–4. YDB-POC-031 verifies no cross-tenant scanning.
