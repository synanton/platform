# EWP-1 — Java Event/Command Contract

**Status:** Implemented across [PR #393](https://github.com/synanton/platform/pull/393) (base records, [`7523fae`](https://github.com/synanton/platform/commit/7523fae137088056d111c11b7e90cb9a230936be)) and [PR #396](https://github.com/synanton/platform/pull/396) (registries and admission checks, [`a288f7b`](https://github.com/synanton/platform/commit/a288f7b9282df73ec2060b1cff60fcdd25b45112)) · **Scope gate:** EWP-0 closed by [#375](https://github.com/synanton/platform/issues/375) · **Frozen source:** [Eventing 1.27 contract](../../api/eventing-contract.md), frozen by [#247](https://github.com/synanton/platform/issues/247)

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

Event tenant scope is emitted in both frozen forms, nested `tenant.id` and top-level `tenant_id`. Producer-facing construction uses the convenience `Event` constructor, which derives both from one `tenantId`; the public canonical constructor remains available for deserialization and rejects a mismatch. Commands use the frozen top-level `tenant_id` form. The `TenantScope` helper checks a message against an already-verified caller tenant; the special `system` value still requires platform-operator authorization at the processing boundary.

`SecurityMetadata` carries a producer claim, not an authorization grant. It contains the actor kind and identifier, classification, policy version, and sorted data-handling tags. It deliberately carries no roles/scopes: consumers must perform authorization independently. Tenant scope has one authoritative top-level field and is not duplicated inside security metadata.

The optional `expected_version` command field is an additive optimistic-concurrency precondition. A value of zero is valid for a not-yet-created aggregate; negative values are rejected.

## Identity, correlation, and causation

- Event and command identities are UUIDs serialized as canonical lowercase hyphenated strings. The contract makes no UUID-version or sortability promise. Deterministic UUIDv5 is valid for repeatable logical identities (as in the current source-event path); UUIDv4 is also valid when its generated ID is persisted and reused.
- Delivery retries reuse the original `event_id` or `command_id`. A retry is not a new fact or a new command.
- `correlation_id` is a UUID shared by messages in one business flow.
- `causation_id` is absent for the root and otherwise names the immediate parent event or command ID. A complete chain has one root; every parent exists in the chain; messages share correlation and tenant; cycles are invalid. `CausationChainValidator` checks complete flow fixtures/batches. A standalone message cannot prove the existence of a parent outside its local data.

## Schema and JSON compatibility

- Event and command versions are independent positive integers serialized as `event_version` and `command_version`.
- **EWP-1 versioning policy clarification:** every event/command schema-shape change, including an additive optional field or enum value, increments that type's `event_version`/`command_version` and adds a new golden fixture. Never rewrite a released version's fixture. A new version preserves the old version's meaning; removals, renames, and semantic changes also require a new version.
- This is a deliberate, stricter Java contract-profile rule than Design 1.27 §12, which permits compatible optional additions within a version. It supersedes the initial “additive-only within a version” wording in merged EWP-1 baseline `7523fae`; future EWP-2/EWP-4 implementations must use this clarified rule.
- `EventTypeDeclaration` registers each supported `(event_type, event_version)` pair; a schema-version bump requires a new entry. A producer/consumer registry lists only versions it supports, and unregistered versions are rejected. `ReplayClass` is event-type-wide and must remain the same across that type's versions. Changing replay semantics requires a new event type, not a schema-version-only change.
- `CommandTypeDeclaration` registers each `(command_type, command_version)` pair.
- `EventingJson.readEvent` and `readCommand` are the validated ingress paths: each deserializes the message and checks its type/version registry before returning it. Unknown or mismatched versions are rejected even when the JSON mapper can parse their fields. A registered schema-version bump is accepted only with its matching version-specific declaration. Direct `ObjectMapper.readValue` is parsing only and must not be treated as message admission.
- Property names are explicit snake_case (except the frozen nested `tenant.id`), timestamps use ISO-8601 UTC strings, and UUIDs use strings. `EventingJson` registers Java time/Optional support and omits absent Optional fields. Its mapper ignores unknown additive fields for defensive parsing; that tolerance is not permission to bypass the type/version registry or change a released version's shape.
- `event.v1.json` and `command.v1.json` are canonical compact JSON fixtures. Serialization and typed-payload round-trip tests fail on shape drift. A deliberate version change must add a new versioned fixture rather than silently rewrite v1.
- `MessageEnvelope` is not a serialized wrapper: event and command correlation fields remain flat as in the frozen projection. No trace ID is added in EWP-1.

## Replay policy

The enum exactly follows Design 1.27 §39: `REPLAY_SAFE`, `REPLAY_WITH_POLICY`, and `NOT_REPLAYABLE`. These classifications apply to event types, not commands. Commands express intent and must not be historically replayed as facts; command delivery retry uses the same command ID.

Each `(event_type, event_version)` pair has one `EventTypeDeclaration`. A schema-version bump adds a declaration for the new pair. `ReplayClass` and its condition are invariants of the event type across versions; the registry rejects a changed replay policy. If replay semantics must change, define a new event type. `REPLAY_WITH_POLICY` requires a non-blank `replay_condition` that states the gate; the other classifications must not carry one. A consumer must not infer replay safety from an event name. Delivery redelivery and policy-approved event replay remain distinct operations.

Commands use the parallel `CommandTypeDeclaration`/`CommandTypeRegistry` `(command_type, command_version)` check; replay classes do not apply to commands.

The draft outline's `REPLAYABLE` / `NON_REPLAYABLE` / `CONDITIONAL` names are intentionally not used: the frozen contract names above are authoritative. `CONDITIONAL` maps to `REPLAY_WITH_POLICY` only when its explicit gate is recorded.

## Invariants

- Record compact constructors reject null required fields, blank identifiers/types, non-positive schema versions/policy versions, invalid optional values, and inconsistent tenant representations.
- `TenantScope.requireMatches(expectedTenantId, message)` rejects absent/blank scope and cross-tenant use before publication or workflow admission. It does not authenticate the caller or grant access.
- Security claims survive serialization but do not confer authorization.
- Event and command payloads are non-null typed values; domain payload schemas are versioned with their containing event/command.

## EWP-1 scope boundary

Included: typed contract records, UUID identity format, correlation/causation validation, schema-version policy, tenant checks, security claims, replay declarations, canonical JSON mapping, and golden/round-trip/invariant tests.

Explicitly excluded: publishers, brokers/transports, outbox/inbox, consumers, retry/backoff, dead letters, persistence, workflow state/engine, and platform integrations. Those remain EWP-2 through EWP-6. BBAG-A Generation A is synchronous and has no runtime dependency on this module; this contract provides no search/authentication implementation.

Contract verification is self-contained: `./gradlew :java:eventing-contract:build` and `./gradlew compileJava` do not require YDB, Cassandra, an LLM endpoint, or the UI package registry. The repository-wide `buildAll` also runs service-backed and UI suites; local reproducibility of those suites depends on their services and package-registry access.
