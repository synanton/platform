# Synanton Design 1.29 — Identity Plane

**Status:** Proposal
**Version:** 1.29
**Date:** 2026-09-05
**Audience:** Architects, platform engineers, developers, SRE, security engineers, integration engineers, and technical decision makers

---

# 1. Executive Summary

Synanton requires a first-class identity architecture that establishes a consistent answer to a fundamental question:

> **Who or what is performing an operation?**

Design 1.29 defines the **Identity Plane**.

The Identity Plane provides the authoritative identity model for:

* human users;
* service identities;
* workloads;
* connectors;
* agents;
* external principals;
* platform components;
* administrative identities.

Identity establishes **who an actor is**.

Authorization establishes **what that actor may do**.

Classification establishes **how a resource or knowledge object is categorized for security purposes**.

These concerns must remain separate.

The architectural relationship is:

```text
Identity
   │
   ▼
Authentication
   │
   ▼
Principal
   │
   ▼
Authorization Policy
   │
   ▼
Decision
   │
   ▼
Resource / Operation
```

The central principle is:

> **Identity establishes a trustworthy principal; authorization decides whether that principal may perform an operation.**

Design 1.29 therefore provides the identity foundation required by Design 1.23 Security and all other platform planes.

---

# 2. Design Position

Identity is a **control-plane capability**.

It is not:

* an application user database;
* an authorization engine;
* an ACL store;
* a content store;
* a security classification system;
* an authentication protocol implementation;
* a specific identity provider.

The architecture defines stable internal contracts while allowing different external identity systems.

Conceptually:

```text
External Identity Providers
          │
          ▼
   Authentication
          │
          ▼
Identity Federation
          │
          ▼
Canonical Synanton Principal
          │
          ▼
Security / Authorization
```

---

# 3. Relationship to Design 1.23

Design 1.23 remains the **normative security architecture**.

Design 1.29 provides the identity substrate required by it.

The distinction is:

```text
Design 1.29
    Who are you?
        │
        ▼
Principal

Design 1.23
    What may you do?
        │
        ▼
Authorization Decision

Design 1.23
    How is data protected?
        │
        ▼
Classification / Representation / Masking
```

Design 1.29 must not redefine Design 1.23 authorization semantics.

Instead, it supplies:

* authenticated principal identity;
* principal type;
* tenant association;
* identity attributes;
* service/workload identity;
* authentication assurance;
* identity lifecycle;
* federation mappings.

---

# 4. Relationship to Design 1.28 — Ingestion

Ingestion operations require an explicit actor identity.

For example:

```text
User
  │
  ▼
Create Ingestion
  │
  ▼
Principal
  │
  ▼
Authorization
  │
  ▼
Ingestion Operation
```

Automated ingestion similarly requires a service or connector identity:

```text
Connector
   │
   ▼
Service Principal
   │
   ▼
Authorization
   │
   ▼
Source Acquisition
```

An ingestion connector must never fabricate user identity.

---

# 5. Relationship to Design 1.27 — Eventing

Events must preserve actor identity when an operation has an attributable actor.

A conceptual event envelope contains:

```yaml
actor:
  principal_id: ...
  principal_type: ...
  tenant_id: ...
```

Events must distinguish:

* actor;
* initiator;
* executing service;
* original requester.

For example:

```text
User
  │
  └── requested operation
          │
          ▼
Workflow
  │
  └── executes operation
          │
          ▼
Service
```

The service executing the operation must not replace the original actor.

---

# 6. Relationship to Design 1.25 — Knowledge

Knowledge objects require provenance.

Identity allows provenance to distinguish:

```text
created_by
processed_by
approved_by
published_by
modified_by
```

A processing run may be executed by a service principal while retaining the initiating actor.

Example:

```text
initiator = user:123
executor  = service:annotation-worker
```

This distinction is important for auditability.

---

# 7. Relationship to Design 1.26 — Content Cache

Content artifacts may have:

* owner;
* creator;
* ingestion actor;
* service executor;
* security scope.

Identity provides the principal references used by the security model.

The Content Cache does not become an identity store.

---

# 8. Relationship to Design 1.30 — AI Runtime

AI Runtime must support workload identity.

For example:

```text
User
  ↓
Application
  ↓
AI Runtime
  ↓
GPU Runtime
```

The GPU runtime should know the authenticated workload identity required for authorization and auditing, without becoming responsible for the entire user identity lifecycle.

AI execution must preserve the distinction between:

```text
requester
executor
resource owner
```

---

# 9. Goals

Design 1.29 has the following goals:

1. Define a canonical Synanton principal model.
2. Support human and non-human identities.
3. Support identity federation.
4. Establish tenant-scoped identity.
5. Provide stable identity references.
6. Support authentication assurance.
7. Support identity lifecycle.
8. Support service and workload identity.
9. Preserve actor identity through asynchronous workflows.
10. Support audit and provenance.
11. Prevent identity spoofing.
12. Allow external identity providers to be replaced.
13. Provide identity contracts to all platform planes.

---

# 10. Non-Goals

Design 1.29 does not mandate:

* a particular identity provider;
* OAuth as the only protocol;
* OIDC as the only federation mechanism;
* LDAP;
* Active Directory;
* a specific IAM product;
* a specific token format;
* a particular database;
* a particular authorization engine;
* a universal enterprise directory;
* biometric authentication;
* password storage architecture.

Authentication mechanisms may evolve independently from the canonical identity model.

---

# 11. Architectural Principles

## 11.1 Identity Is Not Authorization

A principal can be authenticated without being authorized for a particular operation.

```text
Authenticated ≠ Authorized
```

## 11.2 Identity Is Not Classification

A user's identity does not determine the classification of content.

```text
principal = Alice
```

does not imply:

```text
classification = PUBLIC
```

or:

```text
classification = CONFIDENTIAL
```

## 11.3 Stable Internal Identity

External identifiers may change.

Synanton should therefore maintain a stable internal principal identifier.

## 11.4 Explicit Principal Type

Human users, services, workloads, connectors, and agents must not be represented only as strings.

## 11.5 Tenant Scope Is Explicit

Every tenant-scoped principal must have an explicit tenant relationship.

## 11.6 No Implicit Trust

Identity claims from external systems must be validated before becoming trusted Synanton identity attributes.

## 11.7 Original Actor Preservation

Delegation and asynchronous execution must preserve the original actor.

## 11.8 Least Privilege

Service identities should receive only the permissions necessary for their function.

---

# 12. Principal

The canonical identity object is a **Principal**.

Conceptually:

```yaml
Principal:
  principal_id: ...
  type: USER
  status: ACTIVE
  tenant_id: ...
  display_name: ...
  external_identities: [...]
  assurance_level: ...
```

The internal `principal_id` is stable within Synanton.

---

# 13. Principal Types

Initial principal types:

```text
USER
SERVICE
WORKLOAD
CONNECTOR
AGENT
SYSTEM
```

These represent identity semantics rather than implementation technologies.

---

# 14. User Principal

A user represents a human actor.

A user may have:

* one or more external identities;
* tenant membership;
* roles/groups;
* lifecycle state;
* authentication methods;
* assurance level.

Example:

```text
principal_id = user:01...
type         = USER
```

---

# 15. Service Principal

A service principal represents an application or platform service.

Examples:

```text
ingestion-service
workflow-service
search-service
analytics-service
```

Service identity must not be tied to a specific process instance.

---

# 16. Workload Principal

A workload principal represents a running computational workload.

Examples:

```text
worker instance
Kubernetes workload
GPU inference workload
batch processor
```

A workload identity may be short-lived.

The identity itself should remain distinguishable from a process instance.

---

# 17. Connector Principal

Design 1.28 connectors may have dedicated identities.

Example:

```text
connector:sharepoint-prod
```

This allows audit records to answer:

> Which connector acquired this resource?

A connector identity is not equivalent to the human who configured it.

---

# 18. Agent Principal

AI or autonomous agents may require their own principal identity.

An agent must not silently impersonate a user.

Instead:

```text
initiator = user:123
agent     = agent:456
```

may both be preserved.

Authorization must explicitly define what the agent is permitted to do.

---

# 19. System Principal

System operations may have no human initiator.

Examples:

* scheduled reconciliation;
* retention cleanup;
* automatic retry;
* health monitoring.

Such operations should use explicit system principals rather than null actors.

Example:

```text
system:retention
system:reconciliation
system:workflow
```

---

# 20. Principal Lifecycle

A principal has a lifecycle:

```text
PROVISIONED
    │
    ▼
ACTIVE
    │
    ├──────► SUSPENDED
    │           │
    │           ▼
    │         ACTIVE
    │
    ▼
DISABLED
    │
    ▼
DELETED
```

Exact lifecycle semantics depend on principal type.

---

# 21. Identity Deactivation

Deactivation must prevent new authentication or authorization while preserving historical references.

For example:

```text
user:123
```

may become disabled, but historical audit events must continue to reference:

```text
user:123
```

Deleting identity metadata must not erase historical accountability.

---

# 22. Identity Deletion

Identity deletion is different from deactivation.

The architecture should prefer:

> **Disable first; delete only when policy permits.**

Historical references should remain resolvable to a tombstoned identity record where legally required.

---

# 23. External Identity

External identities map external principals to canonical Synanton principals.

Conceptually:

```text
External Identity
       │
       ▼
Identity Mapping
       │
       ▼
Synanton Principal
```

Example:

```yaml
external_identity:
  provider: example-idp
  subject: "00u123..."
  principal_id: "user:01..."
```

---

# 24. External Identifier Stability

The external `subject` must not automatically become the Synanton principal ID.

External providers may:

* migrate;
* rename users;
* change domains;
* change directories.

Synanton's stable identity should survive such changes where the identity continuity is trusted.

---

# 25. Identity Linking

A principal may have multiple external identities.

Example:

```text
Synanton Principal
       │
       ├── OIDC identity
       ├── enterprise directory identity
       └── service identity
```

Identity linking must require appropriate authorization and verification.

Automatic linking based solely on matching display names or email addresses is unsafe.

---

# 26. Authentication

Authentication establishes evidence that a principal controls a credential or trusted identity.

The Identity Plane may consume authentication results from:

* OIDC;
* OAuth;
* mTLS;
* workload identity;
* API credentials;
* other trusted mechanisms.

Authentication itself may be implemented outside the Identity Plane.

---

# 27. Authentication Assurance

Identity should preserve an authentication assurance level.

Conceptually:

```text
LOW
MEDIUM
HIGH
```

The exact assurance taxonomy may evolve.

Authorization policies may require a minimum assurance level for sensitive operations.

---

# 28. Authentication vs Session

Authentication proves identity.

A session represents an authenticated interaction context.

The two must remain separate.

```text
Authentication
      ↓
Authenticated Principal
      ↓
Session / Token
      ↓
Request
```

---

# 29. Session Management

Sessions may include:

* session identifier;
* principal;
* authentication time;
* expiry;
* assurance;
* authentication method;
* client context.

Long-lived sessions must not bypass identity deactivation.

---

# 30. Token Validation

Services receiving identity tokens must validate:

* issuer;
* audience;
* signature;
* expiry;
* not-before;
* subject;
* required claims;
* tenant mapping.

Unvalidated claims must never be treated as trusted identity.

---

# 31. Token Exchange

Service-to-service calls may use token exchange or equivalent delegation.

Example:

```text
User
  │
  ▼
API
  │
  ▼
Workflow
  │
  ▼
Ingestion
```

The downstream service should receive sufficient context to determine:

```text
original principal
delegating service
authorization scope
```

---

# 32. Impersonation

Impersonation must be explicit.

A service acting as a user should produce an auditable relationship:

```text
actor       = service:admin-api
impersonated = user:123
```

Implicit impersonation is prohibited.

---

# 33. Delegation

Delegation is preferred to unrestricted impersonation.

Conceptually:

```text
User
  │
  │ delegates capability
  ▼
Service
  │
  ▼
Operation
```

The delegated authority should be:

* scoped;
* time-limited where appropriate;
* auditable;
* revocable.

---

# 34. Actor Chain

For asynchronous operations, identity context should preserve an actor chain.

Example:

```yaml
actor:
  initiator: user:123
  delegator: service:api
  executor: service:workflow
```

The exact event representation may vary, but semantic preservation is mandatory.

---

# 35. Identity Context

A request context should conceptually contain:

```yaml
identity_context:
  principal_id: ...
  principal_type: ...
  tenant_id: ...
  authentication_assurance: ...
  session_id: ...
  trace_id: ...
```

Security-sensitive services should not trust identity context supplied by arbitrary clients.

---

# 36. Trusted Identity Boundary

Identity becomes trusted only after crossing a trusted authentication boundary.

```text
Untrusted Request
       │
       ▼
Authentication Gateway
       │
       ▼
Validated Identity Context
       │
       ▼
Internal Services
```

Internal services must validate the provenance of propagated identity context.

---

# 37. Tenant Membership

A user may belong to one or more tenants where the deployment model permits it.

Membership should be explicit:

```text
Principal
   │
   ├── Tenant A
   └── Tenant B
```

A request must select an explicit tenant context.

---

# 38. Tenant Context

Tenant selection must be authorized.

The client cannot simply claim:

```text
tenant_id = tenant-B
```

and gain access.

The identity system must establish:

```text
principal membership
+
requested tenant
```

before authorization proceeds.

---

# 39. Service Tenant Scope

Service principals may be:

```text
tenant-scoped
platform-scoped
multi-tenant
```

The scope must be explicit.

A platform service does not automatically gain unrestricted access to every tenant resource.

---

# 40. Tenant Isolation

Identity must support strong tenant isolation.

Cross-tenant identity confusion is a security-critical failure.

For every operation:

```text
principal
+
tenant
+
resource
+
action
```

must be evaluated consistently.

---

# 41. Groups

Groups are collections of principals.

Groups may be:

* locally managed;
* externally federated;
* dynamic;
* platform-defined.

Groups are authorization inputs.

They are not themselves authorization decisions.

---

# 42. Roles

Roles are authorization constructs and therefore belong primarily to the authorization model.

Identity may expose role/group claims when required, but should not make role membership synonymous with identity.

---

# 43. Attributes

Identity attributes may include:

* display name;
* email;
* organizational identifier;
* external subject;
* principal type;
* tenant memberships.

Sensitive or mutable attributes should not be embedded permanently into every event.

Prefer stable principal references plus snapshots where audit requirements require them.

---

# 44. Identity Snapshot

For long-lived audit records, a snapshot may be stored:

```yaml
principal:
  id: user:123
  display_name: Example User
  snapshot_at: ...
```

This preserves historical context even if display metadata changes.

The snapshot must not replace the stable identity reference.

---

# 45. Identity and Audit

Every security-sensitive operation should be attributable.

Minimum audit context:

```text
principal_id
principal_type
tenant_id
operation
resource
timestamp
result
```

Where applicable:

```text
authentication_method
assurance_level
delegator
source_ip
client_id
trace_id
```

---

# 46. Audit Immutability

Audit records must be append-oriented.

Identity changes must not rewrite historical audit facts.

For example:

```text
User name changed
```

does not modify an old audit event.

---

# 47. Identity Events

Identity lifecycle changes should generate events.

Recommended events:

```text
PrincipalCreated
PrincipalActivated
PrincipalSuspended
PrincipalDisabled
PrincipalDeleted
ExternalIdentityLinked
ExternalIdentityUnlinked
TenantMembershipGranted
TenantMembershipRevoked
CredentialChanged
```

These are facts, consistent with Design 1.27.

---

# 48. Identity Event Security

Identity events may contain sensitive information.

Therefore:

* event access must be authorized;
* payloads should contain minimum necessary information;
* credentials and secrets must never be emitted;
* consumers must respect tenant scope.

---

# 49. Identity Event Replay

Identity events may be replayed to rebuild projections.

Consumers must distinguish:

```text
identity state reconstruction
```

from:

```text
external side effect
```

A replay must never automatically trigger actions such as:

* sending emails;
* granting external permissions;
* provisioning external accounts.

---

# 50. Provisioning

Identity provisioning may be:

* manual;
* API-driven;
* SCIM-like;
* event-driven;
* federated.

Provisioning creates or updates the canonical principal.

Provisioning is not authentication.

---

# 51. Deprovisioning

When an external identity is removed, the corresponding Synanton principal should normally be disabled rather than immediately deleted.

This prevents accidental loss of historical identity references.

---

# 52. Just-in-Time Provisioning

Federated users may be provisioned on first authentication.

Conceptually:

```text
External Authentication
        │
        ▼
Unknown external subject
        │
        ▼
Trusted mapping
        │
        ▼
Create / link Principal
        │
        ▼
Authorization
```

JIT provisioning must apply tenant and policy checks before granting access.

---

# 53. Service Identity Provisioning

Services should receive stable identities independent of deployment instances.

For example:

```text
service:ingestion
```

rather than:

```text
pod:ingestion-7f9...
```

The runtime workload may additionally have a workload identity.

---

# 54. Workload Identity

Workload identity should support short-lived credentials.

The preferred model is:

```text
workload starts
    ↓
attests identity
    ↓
receives short-lived credential
    ↓
executes
    ↓
credential expires
```

Long-lived static credentials should be avoided where practical.

---

# 55. Credential Management

The Identity Plane should not become a general-purpose secret store.

Credential material belongs in the platform's secret-management infrastructure.

Identity stores:

```text
credential metadata
```

rather than:

```text
raw private keys
raw passwords
```

unless a specific architecture explicitly requires otherwise.

---

# 56. Credential Rotation

Credentials must support rotation without changing principal identity.

```text
Principal
   │
   ├── Credential A
   └── Credential B
```

Rotation can therefore occur while preserving:

```text
principal_id
```

---

# 57. Credential Revocation

Revocation should take effect within defined operational bounds.

Sensitive credentials should have short lifetimes where possible, reducing reliance on large revocation windows.

---

# 58. API Clients

Applications may have identities separate from their human users.

Example:

```text
Application Principal
       │
       ├── service credential
       └── user delegation
```

The platform must preserve both application and user context when applicable.

---

# 59. Machine-to-Machine Authentication

M2M authentication should identify the calling workload or service.

Example:

```text
service:search
      ↓
service:knowledge
```

The receiving service must authorize the calling principal explicitly.

Network reachability is not authorization.

---

# 60. Internal Service Trust

A service must not assume:

> "It is inside the cluster, therefore it is trusted."

Identity remains required for internal calls.

This is particularly important for:

* Kubernetes;
* GPU workloads;
* workers;
* connectors;
* scheduled jobs.

---

# 61. Identity Propagation

Identity propagation across synchronous calls should preserve:

```text
principal
tenant
delegation
assurance
trace
```

Across asynchronous calls, the event envelope must preserve the necessary immutable identity context.

---

# 62. Identity and Workflow

Design 1.27 workflows may execute long after the original request.

Therefore the workflow must persist the initiating principal reference.

Example:

```text
Workflow
  ├── workflow_id
  ├── initiated_by
  ├── tenant_id
  └── authorization_context
```

A workflow must not rely on an expired user session to determine who initiated it.

---

# 63. Authorization Re-Evaluation

Long-running workflows must not blindly retain authorization forever.

Depending on operation sensitivity, authorization may need to be re-evaluated at execution time.

This creates an important distinction:

```text
who initiated?
```

versus:

```text
is execution still permitted?
```

The first is historical identity.

The second is current authorization.

---

# 64. Identity and Ingestion

An ingestion record should preserve:

```yaml
actor:
  principal_id: ...
  principal_type: ...
  tenant_id: ...

executor:
  principal_id: ...
  principal_type: ...
```

For automated synchronization:

```text
actor = connector/service
executor = connector worker
```

For user-triggered ingestion:

```text
actor = user
executor = ingestion-service
```

---

# 65. Identity and Knowledge Provenance

A knowledge processing run may contain:

```text
source_version
processing_run
initiated_by
executed_by
model_identity
```

This enables questions such as:

> Who requested this processing?

and:

> Which service/model actually performed it?

---

# 66. Identity and AI

AI operations introduce an additional identity distinction:

```text
human initiator
       ↓
application
       ↓
AI agent
       ↓
AI runtime
       ↓
GPU workload
```

Each layer may have its own principal.

The architecture must not collapse these identities into a single "AI user."

---

# 67. Agent Authority

Agents must operate under explicitly bounded authority.

An agent receiving user content must not automatically inherit all user permissions.

Instead:

```text
User authority
      +
Agent policy
      ↓
Effective authority
```

The precise authorization calculation belongs to Design 1.23.

---

# 68. Model Identity

An AI model is generally not a security principal.

A model identifier such as:

```text
model:v1
```

describes an execution dependency, not an authenticated actor.

Model identity belongs in processing provenance, while the runtime/service identity belongs in security context.

---

# 69. GPU Runtime Identity

GPU Runtime should authenticate as a workload/service identity.

Example:

```text
AI Runtime
    ↓
GPU Runtime
    ↓
GPU Worker
```

Each boundary should be authenticated.

The GPU worker must not need to implement Synanton's complete user identity model.

---

# 70. Identity and Content Security

Identity participates in authorization but does not determine content classification.

Conceptually:

```text
Principal
    │
    ▼
Authorization Policy
    │
    ├── resource ACL
    └── classification grants
             │
             ▼
        Access Decision
```

This preserves the Design 1.23 model.

---

# 71. Break-Glass Identity

Break-glass operations require an explicit identity.

The audit record should include:

```text
principal
reason
scope
timestamp
approval context
```

Break-glass must never mean anonymous administrative access.

---

# 72. Administrative Identity

Administrative principals should be distinct from ordinary users.

Possible administrative scopes:

```text
platform administrator
tenant administrator
security administrator
operations administrator
```

Exact privileges remain authorization policy.

Identity only establishes the administrative principal.

---

# 73. Identity Search

The Identity Plane may provide administrative lookup:

```text
get principal
search principals
list tenant members
get external identity mapping
```

Identity search must itself be authorization-controlled.

---

# 74. Privacy

Identity systems contain sensitive information.

The platform should minimize:

* replicated personal attributes;
* unnecessary event payloads;
* long-lived snapshots;
* broad administrative visibility.

Services should receive only the identity attributes they require.

---

# 75. Pseudonymization

Analytics may require pseudonymous principal identifiers.

The identity system should support stable pseudonymous references where appropriate.

For example:

```text
analytics_principal_id
```

must not necessarily expose:

```text
email address
```

---

# 76. Identity Data Classification

Identity metadata should itself be subject to appropriate security controls.

Not every service should be able to retrieve:

* email;
* organizational metadata;
* authentication details;
* external identity mappings.

Access to identity attributes should be separately authorized.

---

# 77. Data Residency

If deployment requirements impose data residency constraints, identity storage and external federation must respect them.

The canonical identity architecture must not require global centralized storage.

---

# 78. Availability

Identity is a critical control-plane dependency.

However, downstream services should avoid unnecessary dependence on live identity lookups for every operation.

Where safe, services may use:

* validated tokens;
* short-lived identity assertions;
* cached non-sensitive identity metadata.

Authorization remains subject to Design 1.23 policy.

---

# 79. Failure Modes

Identity failures include:

```text
authentication unavailable
identity provider unavailable
token invalid
identity mapping unavailable
principal disabled
tenant membership unavailable
credential expired
credential revoked
```

Failures must fail closed for security-sensitive operations.

---

# 80. External Provider Outage

An external identity-provider outage must not automatically invalidate already-established historical identity.

For active requests, behavior depends on authentication/session architecture.

Cached authentication must have explicit lifetime and assurance semantics.

---

# 81. Identity Cache

Identity caching may improve availability.

Cached data must have:

* TTL;
* invalidation mechanism where necessary;
* provenance;
* version;
* security classification.

A stale cache must not bypass principal disablement for sensitive operations.

---

# 82. Identity Versioning

Principal state should have a version or equivalent change indicator.

This can support:

```text
credential changed
membership changed
principal disabled
```

and enable downstream cache invalidation.

---

# 83. Identity Change Propagation

Important identity changes should propagate through Design 1.27 eventing.

Example:

```text
PrincipalDisabled
      ↓
Eventing
      ├── session invalidation
      ├── cache invalidation
      ├── audit
      └── operational notification
```

Consumers remain responsible for appropriate handling.

---

# 84. Authorization Cache Interaction

Identity changes may affect authorization.

Examples:

```text
user disabled
group membership changed
tenant membership revoked
```

Security-sensitive authorization caches must therefore support invalidation or bounded TTL.

This is consistent with Design 1.23.

---

# 85. Identity Integrity

Canonical identity records must have strong integrity guarantees.

A principal must not be mutable by arbitrary application services.

Identity mutations should occur through the Identity Plane contract.

---

# 86. Identity Ownership

The Identity Plane owns:

* principal identity;
* external identity mapping;
* lifecycle;
* identity metadata;
* tenant membership identity relationship;
* authentication association metadata.

The Authorization Plane owns:

* policies;
* grants;
* roles;
* resource ACL evaluation.

---

# 87. Source of Truth

| Concern                   | Authority                          |
| ------------------------- | ---------------------------------- |
| Canonical principal       | Identity Plane                     |
| External identity mapping | Identity Plane                     |
| Principal lifecycle       | Identity Plane                     |
| Authentication result     | Authentication / Identity boundary |
| Authorization policy      | Security / Authorization Plane     |
| Resource ACL              | Security / resource policy         |
| Classification            | Security / Knowledge model         |
| Workflow state            | Workflow Plane                     |
| Source acquisition        | Ingestion Plane                    |
| Knowledge                 | Knowledge Plane                    |
| AI execution state        | AI Runtime                         |

---

# 88. API Model

A conceptual API may provide:

```text
GET    /principals/{id}
POST   /principals
PATCH  /principals/{id}
DELETE /principals/{id}

GET    /principals/{id}/identities
POST   /principals/{id}/identities

GET    /tenants/{id}/members
POST   /tenants/{id}/members
DELETE /tenants/{id}/members/{principal}
```

The exact API protocol is implementation-defined.

---

# 89. Authentication API Boundary

Authentication should expose a separate conceptual boundary:

```text
Authenticate
    ↓
Identity Context
    ↓
Authorization
```

Authentication endpoints must not directly grant resource access.

---

# 90. Identity Context Contract

Internal identity context should be minimal.

Example:

```yaml
identity:
  principal_id: user:01...
  principal_type: USER
  tenant_id: tenant:01...
  assurance_level: HIGH
```

Optional delegation:

```yaml
delegation:
  initiated_by: user:01...
  delegated_by: service:01...
```

---

# 91. Identity Headers and Metadata

Internal transports may propagate identity through:

* signed metadata;
* mTLS identity;
* authenticated RPC context;
* signed tokens.

Plain unsigned headers such as:

```text
X-User-Id: user:123
```

must never be considered authoritative by themselves.

---

# 92. Cryptographic Trust

Where identity context is propagated between independently trusted services, the receiving service must have a cryptographically verifiable basis for trusting the assertion.

Possible mechanisms:

* signed tokens;
* mTLS;
* workload identity;
* authenticated RPC metadata.

---

# 93. Clock and Expiration

Time-bound identity assertions require reliable clock handling.

The architecture should define:

* maximum token lifetime;
* acceptable clock skew;
* expiry behavior;
* renewal behavior.

Expired identity assertions must not authorize new sensitive operations.

---

# 94. Identity Provider Abstraction

The Identity Plane should define a provider abstraction:

```text
IdentityProvider
      │
      ├── authenticate
      ├── resolve
      ├── provision
      └── lifecycle
```

Provider-specific implementation remains outside the domain model.

---

# 95. Multiple Identity Providers

A deployment may integrate multiple providers:

```text
Enterprise IdP
       │
Partner IdP ──► Identity Federation ──► Synanton
       │
Local Identity
```

All must resolve to canonical Synanton principals.

---

# 96. Provider Precedence

When multiple providers exist, identity mapping rules must explicitly define:

* trusted issuer;
* tenant;
* subject namespace;
* linking rules.

Ambiguous mappings must fail closed.

---

# 97. Identity Collision

Two external identities must never silently resolve to the same principal without an explicit trusted linking operation.

For example:

```text
provider-A / subject-123
provider-B / subject-123
```

does not imply the same person.

---

# 98. Identity Recovery

Identity recovery processes must preserve security guarantees.

Recovery should require sufficient proof of control or administrative authorization.

Recovery must not simply rely on mutable profile attributes.

---

# 99. Compromised Identity

If an identity is suspected to be compromised, operators should be able to:

* disable principal;
* revoke credentials;
* terminate sessions;
* revoke delegated authority;
* invalidate relevant caches;
* audit recent activity.

---

# 100. Identity Threat Model

Primary threats include:

1. identity spoofing;
2. token forgery;
3. token replay;
4. tenant confusion;
5. identity collision;
6. privilege escalation;
7. unauthorized impersonation;
8. stale identity cache;
9. credential theft;
10. service identity misuse;
11. agent authority escalation;
12. compromised connector identity.

---

# 101. Threat: Tenant Confusion

Example attack:

```text
authenticated user from Tenant A
        ↓
request claims Tenant B
```

The platform must reject the operation unless the principal is authorized for Tenant B.

---

# 102. Threat: Confused Deputy

A privileged service must not blindly execute requests on behalf of arbitrary users.

The service must evaluate:

```text
original actor
+
delegation
+
requested operation
```

according to authorization policy.

---

# 103. Threat: Token Replay

Tokens should have:

* bounded lifetime;
* appropriate audience;
* issuer validation;
* replay protections where required.

High-risk operations may require stronger proof of possession or equivalent controls.

---

# 104. Threat: Service Credential Theft

Service credentials should be:

* short-lived where possible;
* scoped;
* rotated;
* auditable;
* revocable.

Static credentials should not be embedded in application source code.

---

# 105. Threat: Agent Escalation

An AI agent must not infer authority from the content of a user request.

Example:

```text
"Act as an administrator."
```

is not an identity assertion.

Agent authority must originate from authenticated platform identity and authorization policy.

---

# 106. Threat: Connector Impersonation

A connector must not be able to claim:

```text
actor = user:123
```

unless a trusted delegation mechanism explicitly establishes that relationship.

---

# 107. Threat: Identity Injection

Untrusted source content must never be interpreted as identity metadata.

This is especially important for Design 1.28 ingestion.

For example, a document containing:

```text
owner: administrator
```

does not establish an administrative principal.

---

# 108. Observability

Identity-aware telemetry should include:

```text
principal_id
principal_type
tenant_id
trace_id
operation
result
```

Sensitive attributes such as credentials must never appear in logs.

---

# 109. Metrics

Recommended identity metrics:

```text
authentication_success_total
authentication_failure_total
principal_creation_total
principal_disable_total
identity_mapping_failure_total
token_validation_failure_total
delegation_failure_total
identity_provider_latency
identity_provider_errors
```

---

# 110. Audit Correlation

Identity should integrate with the platform-wide trace model:

```text
trace_id
correlation_id
causation_id
principal_id
tenant_id
```

This permits investigation across:

```text
API
↓
Workflow
↓
Ingestion
↓
Processing
↓
AI Runtime
↓
GPU Runtime
```

---

# 111. Identity and Analytics

Analytics may aggregate:

* active principals;
* service activity;
* authentication failures;
* tenant activity;
* workflow activity.

Raw identity attributes should not automatically flow into analytics.

Use minimized or pseudonymous identifiers where possible.

---

# 112. Identity and Search

Search results must be authorized using the caller's principal.

Search indexing must not copy identity information unnecessarily.

Security-aware search follows Design 1.23.

---

# 113. Identity and Reporting

Reports may require attribution:

```text
created_by
approved_by
executed_by
```

Reports must apply identity and authorization policies before exposing such data.

---

# 114. Identity and Recalculation

A recalculation workflow may have:

```text
initiated_by = user
executed_by = Equalix service
```

If the operation requires current authorization, Equalix must re-evaluate authorization rather than assuming the original authorization remains valid indefinitely.

---

# 115. Identity and Retention

Identity audit records may have retention requirements distinct from operational identity metadata.

For example:

```text
active profile
```

may be deleted or minimized while:

```text
historical audit identity reference
```

remains retained.

---

# 116. Implementation Architecture

A reference implementation should use clear boundaries:

```text
identity-domain
identity-application
identity-provider-spi
identity-federation
identity-store
authentication-adapter
tenant-membership
identity-events
audit-adapter
observability
```

The domain model must not depend directly on a specific identity provider.

---

# 117. Domain Model

Initial entities:

```text
Principal
ExternalIdentity
PrincipalMembership
PrincipalStatus
AuthenticationRecord
Delegation
IdentityProvider
IdentityProviderMapping
IdentitySnapshot
```

---

# 118. Principal Identifier

Principal identifiers should be:

* globally unique within Synanton;
* opaque;
* stable;
* non-reusable.

A deleted principal ID must not be reassigned to a new principal.

---

# 119. External Identity Identifier

External identity identifiers should preserve:

```text
issuer
subject
```

rather than relying on subject alone.

Conceptually:

```text
(issuer, subject) → principal_id
```

---

# 120. Persistence

A relational database is a suitable initial implementation candidate for:

* principal metadata;
* external identity mappings;
* memberships;
* lifecycle state;
* provider configuration metadata;
* identity version.

Secrets should remain outside the identity database where possible.

---

# 121. Transactional Integrity

Identity changes that affect multiple records should be atomic.

For example:

```text
link external identity
+
update principal mapping
+
emit event
```

must not leave an ambiguous mapping.

Design 1.27 outbox patterns should be used for reliable event publication.

---

# 122. Concurrency

Concurrent identity mutations require optimistic concurrency or equivalent controls.

Example:

```text
Principal version 10
        │
        ├── update A
        └── update B
```

One update must not silently overwrite the other.

---

# 123. Identity Cache

An identity cache may be used for:

* principal metadata;
* provider metadata;
* group resolution.

Cache keys must include tenant context where relevant.

Sensitive authorization decisions must not rely indefinitely on stale cache state.

---

# 124. Eventual Consistency

Some identity projections may be eventually consistent.

The canonical principal store remains authoritative.

Security-critical lifecycle changes require stronger consistency or explicit invalidation semantics.

---

# 125. Implementation Phases

## Phase 1 — Canonical Identity Model

Implement:

* Principal;
* principal types;
* lifecycle;
* stable IDs;
* tenant scope.

### Exit Criteria

All platform identity references use canonical principal IDs.

---

## Phase 2 — External Identity Mapping

Implement:

* identity provider abstraction;
* issuer/subject mapping;
* linking;
* collision detection.

### Exit Criteria

A federated identity can authenticate and resolve to one canonical principal deterministically.

---

## Phase 3 — Authentication Context

Implement:

* validated authentication context;
* assurance;
* session/token integration;
* identity propagation.

### Exit Criteria

Downstream services can reliably identify authenticated callers.

---

## Phase 4 — Service and Workload Identity

Implement:

* service principals;
* workload principals;
* short-lived credentials;
* service-to-service authentication.

### Exit Criteria

Core platform services operate without shared static credentials.

---

## Phase 5 — Delegation

Implement:

* initiator/executor distinction;
* delegated authority;
* explicit impersonation semantics;
* asynchronous actor propagation.

### Exit Criteria

A workflow can preserve the original actor across multiple asynchronous stages.

---

## Phase 6 — Identity Events

Implement:

* lifecycle events;
* outbox;
* event versioning;
* cache invalidation.

### Exit Criteria

Identity lifecycle changes reliably propagate to dependent services.

---

## Phase 7 — Security Integration

Integrate with Design 1.23:

* authorization;
* tenant isolation;
* ACL evaluation;
* security audit;
* break-glass.

### Exit Criteria

Identity cannot be used to bypass the normative security model.

---

## Phase 8 — Production Hardening

Implement:

* credential rotation;
* recovery;
* provider outage handling;
* threat testing;
* observability;
* operational tooling.

### Exit Criteria

Identity remains secure and operational during provider and infrastructure failures.

---

# 126. Initial Vertical Slice

The first vertical slice should be:

```text
External IdP
    ↓
Authentication
    ↓
Principal Resolution
    ↓
Tenant Membership
    ↓
Authorization
    ↓
Protected API
    ↓
Audit Event
```

This validates the fundamental identity/security boundary.

---

# 127. Second Vertical Slice

The second slice should establish service identity:

```text
Service A
    ↓
Workload Identity
    ↓
Service B
    ↓
Authorization
    ↓
Audit
```

No shared static service credential should be required.

---

# 128. Third Vertical Slice

The third slice should validate asynchronous identity:

```text
User
  ↓
API
  ↓
Workflow
  ↓
Ingestion
  ↓
Processing
  ↓
AI Runtime
```

The resulting audit/provenance chain should preserve:

```text
initiator
executor
tenant
```

throughout.

---

# 129. Acceptance Criteria

Design 1.29 is implementation-ready when:

### Identity

* canonical principal IDs exist;
* IDs are stable and non-reusable;
* principal types are explicit;
* lifecycle is defined.

### Federation

* external identities map deterministically;
* issuer and subject are handled safely;
* identity collisions fail closed.

### Tenant Isolation

* tenant membership is explicit;
* tenant claims cannot be self-authoritative;
* cross-tenant access tests fail closed.

### Authentication

* identity assertions are validated;
* expiry is enforced;
* assurance is represented.

### Services

* services have distinct identities;
* workload identity is supported;
* static shared credentials are not required.

### Delegation

* initiator and executor are distinct;
* impersonation is explicit;
* delegation is auditable.

### Eventing

* identity lifecycle events are durable;
* events are versioned;
* consumers are idempotent.

### Security

* identity integrates with Design 1.23;
* identity cannot bypass authorization;
* break-glass operations are attributable.

### Operations

* identity failures are observable;
* provider outage behavior is defined;
* credential rotation is supported;
* compromised identities can be disabled.

---

# 130. Security Invariants

The following are mandatory:

1. **Authentication does not imply authorization.**
2. **Identity does not imply classification.**
3. **External identity claims are untrusted until validated.**
4. **Tenant identity is never accepted solely from client input.**
5. **Principal IDs are stable and never reused.**
6. **External subject identifiers are scoped by issuer.**
7. **A service cannot silently impersonate a user.**
8. **Delegation must be explicit and auditable.**
9. **Agent identity must remain distinct from user identity.**
10. **System operations use explicit system principals.**
11. **Credentials are not identity records.**
12. **Credentials must not appear in events or logs.**
13. **Identity lifecycle changes are auditable.**
14. **Disabled identities cannot initiate new authorized operations.**
15. **Historical identity references must remain attributable.**
16. **Internal service communication requires authenticated identity.**
17. **Network location is not an authorization decision.**
18. **Identity context propagated internally must have a trusted origin.**
19. **Identity cache staleness must not bypass critical security changes.**
20. **Identity is authoritative for principals, not authorization policy.**

---

# 131. Reference Identity Flow

The complete security flow becomes:

```text
                  External Identity
                         │
                         ▼
                ┌─────────────────┐
                │ Authentication  │
                └────────┬────────┘
                         │
                         ▼
                ┌─────────────────┐
                │ Identity 1.29   │
                │ Canonical       │
                │ Principal       │
                └────────┬────────┘
                         │
                         ▼
                ┌─────────────────┐
                │ Security 1.23   │
                │ Authorization   │
                └────────┬────────┘
                         │
                         ▼
                    Decision
                         │
              ┌──────────┼──────────┐
              ▼          ▼          ▼
          Ingestion   Knowledge   AI Runtime
            1.28        1.25         1.30
              │          │            │
              └──────────┼────────────┘
                         ▼
                    Eventing 1.27
```

---

# 132. Complete Synanton Plane Model

With Designs 1.23–1.30, the architecture can be expressed as:

```text
                         ┌─────────────────────┐
                         │   External World    │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │   Ingestion 1.28    │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │ Content Cache 1.26  │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │ Knowledge 1.25      │
                         └──────────┬──────────┘
                                    │
                       ┌────────────┼────────────┐
                       ▼            ▼            ▼
                    Search      Analytics    Applications

┌───────────────────────────────────────────────────────────────┐
│                       Eventing 1.27                           │
│              events / commands / workflows                   │
└───────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────┐
│                       Security 1.23                           │
│        authorization / classification / policy / ACL          │
└───────────────────────────────────────────────────────────────┘
                              ▲
                              │
                    ┌─────────┴─────────┐
                    │    Identity 1.29  │
                    │ principal / trust │
                    └─────────┬─────────┘
                              │
                    Authentication
                              │
                         External IdP

┌───────────────────────────────────────────────────────────────┐
│                       AI Runtime 1.30                        │
│             AI execution / models / workloads                 │
└───────────────────────────────┬───────────────────────────────┘
                                │
                                ▼
                         GPU Runtime
```

---

# 133. Conceptual Separation

The resulting platform has explicit answers to different questions:

```text
Who are you?
    ↓
Identity 1.29

Can you do this?
    ↓
Security / Authorization 1.23

What is the source?
    ↓
Ingestion 1.28

What does the source contain?
    ↓
Extraction

What does Synanton understand?
    ↓
Knowledge 1.25

How is the information stored/retrieved?
    ↓
Content Cache / Search

How is work coordinated?
    ↓
Eventing / Workflow 1.27

How is AI computation executed?
    ↓
AI Runtime 1.30

How is activity measured?
    ↓
Analytics 1.25
```

This separation is an architectural invariant rather than merely an implementation preference.

---

# 134. Recommended Terminology

The following terminology should be standardized:

| Term              | Meaning                                                   |
| ----------------- | --------------------------------------------------------- |
| Principal         | Canonical Synanton identity                               |
| User              | Human principal                                           |
| Service           | Application/service principal                             |
| Workload          | Runtime computational principal                           |
| Connector         | Source-integration principal                              |
| Agent             | Autonomous/software agent principal                       |
| System Principal  | Platform-generated actor                                  |
| External Identity | Identity represented by an external provider              |
| Authentication    | Establishing identity evidence                            |
| Identity Mapping  | Mapping external identity to principal                    |
| Delegation        | Explicit transfer of bounded authority                    |
| Impersonation     | Explicit representation of one actor as another           |
| Tenant Membership | Principal's relationship to a tenant                      |
| Assurance         | Strength of authentication evidence                       |
| Identity Context  | Trusted identity information propagated with an operation |

---

# 135. Implementation Readiness Checklist

Before implementation begins:

* [ ] Define canonical `Principal`.
* [ ] Define principal types.
* [ ] Define stable principal identifier format.
* [ ] Define principal lifecycle.
* [ ] Define external identity mapping.
* [ ] Define issuer/subject handling.
* [ ] Define tenant membership model.
* [ ] Define authentication assurance.
* [ ] Define service identity.
* [ ] Define workload identity.
* [ ] Define delegation model.
* [ ] Define actor-chain propagation.
* [ ] Define identity context contract.
* [ ] Define identity event schemas.
* [ ] Define transactional outbox.
* [ ] Define identity cache semantics.
* [ ] Define lifecycle invalidation.
* [ ] Define audit records.
* [ ] Define provider abstraction.
* [ ] Define provider outage behavior.
* [ ] Define credential rotation integration.
* [ ] Define break-glass identity semantics.
* [ ] Define privacy/minimization policy.
* [ ] Define security and threat tests.
* [ ] Define cross-tenant negative tests.
* [ ] Define first federated identity integration.
* [ ] Define first service/workload identity integration.

---

# Appendix A — Recommended Principal Model

```yaml
principal:
  principal_id: principal:01...
  type: USER
  status: ACTIVE

  tenant_memberships:
    - tenant_id: tenant:01...
      status: ACTIVE

  external_identities:
    - provider: example-idp
      issuer: https://idp.example
      subject: "..."

  assurance:
    level: HIGH
    authenticated_at: ...

  metadata:
    display_name: Example User

  version: 7
```

---

# Appendix B — Recommended Actor Context

```yaml
actor_context:
  initiator:
    principal_id: user:01...
    principal_type: USER

  delegator:
    principal_id: service:01...
    principal_type: SERVICE

  executor:
    principal_id: service:02...
    principal_type: SERVICE

  tenant_id: tenant:01...

  assurance_level: HIGH

  trace_id: ...
  correlation_id: ...
```

The actual implementation may simplify this representation, but the semantic distinction must remain.

---

# Appendix C — Recommended Identity Events

```text
PrincipalCreated
PrincipalActivated
PrincipalSuspended
PrincipalDisabled
PrincipalDeleted

ExternalIdentityLinked
ExternalIdentityUnlinked

TenantMembershipGranted
TenantMembershipRevoked

CredentialRegistered
CredentialRevoked
CredentialRotated

DelegationGranted
DelegationRevoked
```

---

# Appendix D — Recommended Failure Taxonomy

```text
AUTHENTICATION_FAILED
TOKEN_INVALID
TOKEN_EXPIRED
TOKEN_AUDIENCE_INVALID
TOKEN_ISSUER_INVALID

IDENTITY_NOT_FOUND
IDENTITY_DISABLED
IDENTITY_SUSPENDED

IDENTITY_MAPPING_AMBIGUOUS
IDENTITY_MAPPING_CONFLICT

TENANT_MEMBERSHIP_DENIED
TENANT_CONTEXT_INVALID

DELEGATION_INVALID
DELEGATION_EXPIRED
IMPERSONATION_DENIED

CREDENTIAL_REVOKED
CREDENTIAL_EXPIRED

IDENTITY_PROVIDER_UNAVAILABLE
IDENTITY_STORE_UNAVAILABLE
```

---

# Appendix E — Design Dependency Summary

| Design      | Relationship                                                 |
| ----------- | ------------------------------------------------------------ |
| Design 1.23 | Normative security and authorization model                   |
| Design 1.25 | Knowledge, provenance and analytics consume identity context |
| Design 1.26 | Content artifacts reference identity/security context        |
| Design 1.27 | Events/workflows propagate actor and executor identity       |
| Design 1.28 | Ingestion requires authenticated source and actor identity   |
| Design 1.29 | Canonical identity and identity lifecycle                    |
| Design 1.30 | AI Runtime and workloads consume identity context            |

---

# Appendix F — Architectural Decisions

| Decision              | Choice                            |
| --------------------- | --------------------------------- |
| Canonical identity    | Synanton Principal                |
| Principal ID          | Stable opaque identifier          |
| External identity     | `(issuer, subject)` mapping       |
| Authentication        | Provider-independent              |
| Authorization         | Separate; Design 1.23             |
| Classification        | Separate; Design 1.23             |
| Human identity        | USER                              |
| Service identity      | SERVICE                           |
| Runtime identity      | WORKLOAD                          |
| Connector identity    | CONNECTOR                         |
| Agent identity        | AGENT                             |
| Platform identity     | SYSTEM                            |
| Tenant scope          | Explicit                          |
| Delegation            | Explicit                          |
| Impersonation         | Explicit and auditable            |
| Service trust         | Authenticated identity required   |
| Credentials           | Externalized from identity domain |
| Lifecycle             | Durable                           |
| Identity events       | Eventing 1.27                     |
| Audit                 | Append-oriented                   |
| Provider              | Abstract                          |
| Storage               | Abstract                          |
| Cache                 | Allowed with bounded staleness    |
| Cross-tenant identity | Fail closed                       |
| Principal ID reuse    | Prohibited                        |

---

# Appendix G — Final Architectural Thesis

Design 1.29 establishes identity as the **trust foundation of Synanton**.

The critical distinction is:

```text
Identity
    =
Who is acting?

Authorization
    =
What may that actor do?

Classification
    =
How is the protected object categorized?

Provenance
    =
Why does this object or knowledge exist?

Execution Identity
    =
Which service/workload actually performed the operation?
```

These questions must not be collapsed into one mechanism.

The resulting architecture is:

> **Identity establishes the principal. Security establishes authority. Ingestion establishes source state. Knowledge establishes derived meaning. Eventing coordinates change. AI Runtime executes computation.**

The most important invariant is therefore:

> **Every security-relevant operation in Synanton must have an attributable, tenant-scoped, trusted principal identity, while authorization remains a separate and independently evaluated decision.**

This makes Identity 1.29 the natural control-plane foundation for the rest of the Synanton architecture.