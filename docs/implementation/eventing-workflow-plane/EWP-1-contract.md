# EWP-1 — Java Event/Command Contract

**Status:** Draft implementation profile · **Scope gate:** EWP-0 closed by [#375](https://github.com/synanton/platform/issues/375) · **Frozen source:** [Eventing 1.27 contract](../../api/eventing-contract.md), frozen by [#247](https://github.com/synanton/platform/issues/247)

This document records the Java contract profile implemented by `java/eventing-contract`. It implements the frozen Event/Command boundary; it does not revise Design 1.27 or define publication/workflow execution.

## Types and serialized fields

`Event<T>` and `Command<T>` are immutable generic records. `T` is a typed payload schema owned by the event/command type; raw `Map<String, Object>` payloads and raw generic messages are not the contract.

| Type | Required fields | Optional fields |
|---|---|---|
| `Event<T>` | `event_id`, `event_type`, `event_version`, `occurred_at`, `producer`, `tenant`, `tenant_id`, `correlation_id`, `replay_class`, `security`, `payload` | `subject_id`, `causation_id`, `replay_condition` |
| `Command<T>` | `command_id`, `command_type`, `command_version`, `issued_at`, `tenant_id`, `security`, `target`, `correlation_id`, `payload` | `causation_id`, `expected_version` |
| `Producer` | `service`, `version` | — |
| `SecurityMetadata` | `actor_type`, `actor_id`, `classification`, `policy_version`, `data_handling_tags` | — |
| `CommandTarget` | `type`, `id` | — |

Event tenant scope is emitted in both frozen forms, nested `tenant.id` and top-level `tenant_id`. Construction and deserialization reject a mismatch; both are derived from the same scope at normal construction. Commands use the frozen top-level `tenant_id` form. The `TenantScope` helper checks a message against an already-verified caller tenant; the special `system` value still requires platform-operator authorization at the processing boundary.

`SecurityMetadata` carries a producer claim, not an authorization grant. It contains the actor kind and identifier, classification, policy version, and sorted data-handling tags. It deliberately carries no roles/scopes: consumers must perform authorization independently. Tenant scope has one authoritative top-level field and is not duplicated inside security metadata.

The optional `expected_version` command field is an additive optimistic-concurrency precondition. A value of zero is valid for a not-yet-created aggregate; negative values are rejected.

## Identity, correlation, and causation

- Event and command identities are UUIDs serialized as canonical lowercase hyphenated strings. The contract makes no UUID-version or sortability promise. Deterministic UUIDv5 is valid for repeatable logical identities (as in the current source-event path); UUIDv4 is also valid when its generated ID is persisted and reused.
- Delivery retries reuse the original `event_id` or `command_id`. A retry is not a new fact or a new command.
- `correlation_id` is a UUID shared by messages in one business flow.
- `causation_id` is absent for the root and otherwise names the immediate parent event or command ID. A complete chain has one root; every parent exists in the chain; messages share correlation and tenant; cycles are invalid. `CausationChainValidator` checks complete flow fixtures/batches. A standalone message cannot prove the existence of a parent outside its local data.

## Schema and JSON compatibility

- Event and command versions are independent positive integers serialized as `event_version` and `command_version`.
- Within one version, changes are additive and optional. Removing/renaming a field or changing its meaning requires a new version; a new version preserves the old version's meaning and golden fixture.
- Property names are explicit snake_case (except the frozen nested `tenant.id`), timestamps use ISO-8601 UTC strings, and UUIDs use strings. `EventingJson` is the canonical mapper; it registers Java time/Optional support, omits absent Optional fields, and ignores unknown additive fields on input.
- `event.v1.json` and `command.v1.json` are canonical compact JSON fixtures. Serialization and typed-payload round-trip tests fail on shape drift. A deliberate version change must add a new versioned fixture rather than silently rewrite v1.
- `MessageEnvelope` is not a serialized wrapper: event and command correlation fields remain flat as in the frozen projection. No trace ID is added in EWP-1.

## Replay policy

The enum exactly follows Design 1.27 §39: `REPLAY_SAFE`, `REPLAY_WITH_POLICY`, and `NOT_REPLAYABLE`. These classifications apply to event types, not commands. Commands express intent and must not be historically replayed as facts; command delivery retry uses the same command ID.

Each event type has one `EventTypeDeclaration`. `EventTypeRegistry` rejects undeclared types and event instances whose classification differs from that declaration. `REPLAY_WITH_POLICY` requires a non-blank `replay_condition` that states the gate; the other classifications must not carry one. A consumer must not infer replay safety from an event name. Delivery redelivery and policy-approved event replay remain distinct operations.

The draft outline's `REPLAYABLE` / `NON_REPLAYABLE` / `CONDITIONAL` names are intentionally not used: the frozen contract names above are authoritative. `CONDITIONAL` maps to `REPLAY_WITH_POLICY` only when its explicit gate is recorded.

## Invariants

- Record compact constructors reject null required fields, blank identifiers/types, non-positive schema versions/policy versions, invalid optional values, and inconsistent tenant representations.
- `TenantScope.requireMatches(expectedTenantId, message)` rejects absent/blank scope and cross-tenant use before publication or workflow admission. It does not authenticate the caller or grant access.
- Security claims survive serialization but do not confer authorization.
- Event and command payloads are non-null typed values; domain payload schemas are versioned with their containing event/command.

## EWP-1 scope boundary

Included: typed contract records, UUID identity format, correlation/causation validation, schema-version policy, tenant checks, security claims, replay declarations, canonical JSON mapping, and golden/round-trip/invariant tests.

Explicitly excluded: publishers, brokers/transports, outbox/inbox, consumers, retry/backoff, dead letters, persistence, workflow state/engine, and platform integrations. Those remain EWP-2 through EWP-6. BBAG-A Generation A is synchronous and has no runtime dependency on this module; this contract provides no search/authentication implementation.
