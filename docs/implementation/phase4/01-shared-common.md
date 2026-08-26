# 01 - shared/common - Phase 4 - Sanitisation & Validation Primitives

**Version:** 1.0
**Date:** 2026-08-11
**Status:** Draft for review
**Depends on:** Phase 3 `shared/common` DoD (Kafka wrapper, `TraceIdFilter`, MDC context)
**Scope:** Ship the two cross-cutting security primitives every REST- or gRPC-boundary service will depend on in Phase 4: `SanitizingStringDeserializer` (OWASP Jackson deserialiser for REST bodies) and `PgvValidatingServerInterceptor` (protoc-gen-validate interceptor for gRPC servers). Both live in `shared/common` so no other module has to re-implement or re-configure them.

---

## 1. Context and Document Alignment

| Source | Role in this plan |
|--------|-------------------|
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §24.1 Global JSON sanitisation *(v1.18)* | Production target for the REST sanitiser primitive |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §24 Jakarta Validation on public DTOs *(v1.18)* | Companion validation surface; `shared/common` publishes the shared constraint annotations |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §28-§32 note: gRPC validation with protoc-gen-validate *(v1.18)* | Production target for the gRPC PGV interceptor primitive |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §45 v1.18 metric catalogue | Metrics this library must emit |

**Explicit non-goals for Phase 4:**

- No per-tenant sanitiser policy overrides. `topology.tenant_policy.security_sanitizer_overrides` schema is defined by `10-topology.md` but the deserialiser reads the *platform* default. Per-tenant override is a Phase 5 follow-on.
- No custom validation annotations beyond the canonical set below. If a service needs a bespoke constraint it declares it locally.
- No PGV Java code generation in this plan - `shared/common` ships only the *interceptor* and BOM. Each service owns its `.proto` files and PGV rules per `08-synapt.md`, `10-topology.md`, etc.

---

## 2. Phase 4 in One Sentence

> Publish two library components (REST body sanitiser and gRPC PGV interceptor) plus the canonical Jakarta Validation constraint annotations, so that every downstream service enables input hardening by adding a single dependency and one `@Configuration` bean.

---

## 3. Target Architecture

```mermaid
flowchart LR
  REST[Any Spring REST service] --> JB[Jackson2ObjectMapperBuilderCustomizer]
  JB --> SD[SanitizingStringDeserializer<br/>OWASP HTML Sanitizer]
  SD --> CTRL[Controller receives clean String]
  CTRL --> VAL[JSR-380 Bean Validation<br/>@NotBlank @Size @Pattern]
  VAL --> HDL[RestControllerAdvice → 400 field_errors[]]
  GRPC[Any gRPC service] --> INT[PgvValidatingServerInterceptor]
  INT --> PGV[PGV Validator.check(msg)]
  PGV -->|invalid| STAT[Status.INVALID_ARGUMENT + BadRequest]
  PGV -->|valid| SVC[gRPC service impl]
```

---

## 4. Deliverables

### 4.1 `com.synanton.common.security.sanitizer` package

- `SanitizingStringDeserializer extends JsonDeserializer<String>` - runs the OWASP HTML sanitizer with a policy configured at construction time; increments `synapt_sanitization_applied_total{tenant,field}` when input differs from output; increments `synapt_sanitization_skipped_total{tenant,field}` when the field's setter carries `@AllowHtml`.
- `@AllowHtml` - runtime-retained marker annotation for DTO fields that legitimately carry HTML (e.g. `chunk_content` on ingestion webhook payloads). The deserialiser looks up the target `BeanProperty` and skips sanitisation if the annotation is present.
- `HtmlSanitizerPolicyFactory` - builds `PolicyFactory` from the platform config keys:

```yaml
synanton.sanitizer:
  enabled: true
  allowed-tags: [b, i, em, strong, code, pre, blockquote, ul, ol, li, p, br]
  allowed-attributes: [href, title]
  strip-unsafe-css: true
  allow-relative-links: false
```

- `SanitizingModule extends SimpleModule` - registers the deserialiser for `String.class`. Downstream services install it via `Jackson2ObjectMapperBuilderCustomizer`:

```java
@Bean
Jackson2ObjectMapperBuilderCustomizer sanitizingCustomizer(SanitizingModule module) {
    return builder -> builder.modules(module);
}
```

### 4.2 `com.synanton.common.validation.constraints` package

Ships the canonical constraint annotations referenced across every service so identifier formats never diverge:

| Annotation | Composed from | Applies to |
|---|---|---|
| `@TenantId` | `@NotBlank @Pattern(regexp = "^[a-zA-Z0-9_-]{1,64}$")` | tenant_id, user_subject |
| `@ResourceId` | `@NotBlank @Pattern(regexp = "^[a-fA-F0-9-]{36}$")` | content_ref_id, chunk_id, entity_id, grant_id |
| `@IdempotencyKey` | `@NotBlank @Size(min=1, max=256)` | headers named `Idempotency-Key` |
| `@QueryText` | `@NotBlank @Size(max=10000)` | `POST /search` `query` field |
| `@FreeText` | `@Size(max=1024)` | `display_name`, `title`, `label` |
| `@AllowHtml` | *marker* | overrides sanitiser (see §4.1) |

Global `@RestControllerAdvice` (also shipped): `ValidationExceptionHandler` translates `MethodArgumentNotValidException` and `ConstraintViolationException` to:

```json
HTTP 400 Bad Request
{
  "type": "https://synanton.org/errors/validation",
  "title": "Validation failed",
  "status": 400,
  "field_errors": [
    { "field": "query", "error": "must not be blank" },
    { "field": "top_n", "error": "must be less than or equal to 10000" }
  ]
}
```

Emit metric `synapt_validation_rejected_total{tenant,field,error}` on strict-mode rejection; `synapt_validation_lenient_warning_total{tenant,field,error}` when strict mode is off. The `strict` flag is read from `synapt.validation.strict` in Spring config; the advice pattern is service-agnostic (relies on Spring core, not `synapt`).

### 4.3 `com.synanton.common.grpc.validation` package

- `PgvValidatingServerInterceptor implements ServerInterceptor` - invokes `Validator.check(message)` on messages implementing PGV's `io.envoyproxy.pgv.ValidatorImpl`. On failure: closes call with `Status.INVALID_ARGUMENT`, attaches `com.google.rpc.BadRequest` payload with `field_violations[]`, increments `grpc_validation_failed_total{service,method,field,error}`.
- `PgvValidatingConfiguration @Configuration` - auto-registers the interceptor on every `ServerBuilder` if `grpc.validation.enabled: true` (default).
- Companion Gradle plugin config example (per-service; documented but not shipped):

```gradle
// each service's build.gradle.kts
protobuf {
  plugins {
    id("pgv-java") { artifact = "build.buf:protoc-gen-validate:${libs.versions.pgv.get()}" }
  }
  generateProtoTasks {
    all().forEach { it.plugins.create("pgv-java") { option("lang=java") } }
  }
}
```

### 4.4 Test kit

- `SanitizerTestKit` (published in `shared/common/src/testFixtures`) - preloaded XSS payload list from OWASP XSS filter evasion cheatsheet; each downstream service reuses it in a Testcontainers integration test.
- `PgvViolationAssertions` - static assertion helpers for gRPC tests: `assertRejectedField(response, "tenant_id", "string.pattern")`.

---

## 5. Module Boundaries

| Module | Owns in Phase 4 | Does not own |
|---|---|---|
| `shared/common` | Sanitiser deserialiser, `@AllowHtml`, canonical constraints, `RestControllerAdvice`, PGV interceptor, test kit | Per-tenant policy resolution; running sanitiser on responses; PGV `.proto` files |
| `synapt` | Wires the `SanitizingModule` into its `ObjectMapper`; declares `synapt.sanitizer.*` config; ships `synapt.validation.strict` flag | The deserialiser itself |
| gRPC services (`topology`, `relix`, `synflux-router`) | `.proto` files with `(validate.rules)`; register `PgvValidatingServerInterceptor` on their `ServerBuilder` | The interceptor code |

---

## 6. Prerequisites

| # | Prereq | Home | Notes |
|---|---|---|---|
| 1 | `shared/common` Phase 3 DoD met (Kafka wrapper, MDC filter) | - | Non-negotiable |
| 2 | Add `com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20240325.1` to BOM | `gradle/libs.versions.toml` | Runtime dep for sanitiser |
| 3 | Add `build.buf:protoc-gen-validate:1.2.1` to BOM | `gradle/libs.versions.toml` | Runtime dep for PGV interceptor + build-time plugin |
| 4 | Micrometer already on classpath (used since Phase 2) | shared BOM | Metric emission |
| 5 | Jakarta Bean Validation 3.0 (Spring Boot 3.x default) | shared BOM | Constraint composition |

---

## 7. Task Breakdown

| # | Task | Deliverable | Est. |
|---|---|---|---|
| SC4-1 | Add OWASP + PGV deps to BOM; publish `shared/common` snapshot | Updated `libs.versions.toml` | 0.25 day |
| SC4-2 | Implement `HtmlSanitizerPolicyFactory` reading config keys; unit test against XSS payload corpus | Class + unit tests | 1 day |
| SC4-3 | Implement `SanitizingStringDeserializer` + `SanitizingModule`; unit test with mock `BeanProperty` carrying `@AllowHtml` | Class + unit tests | 1 day |
| SC4-4 | Ship canonical constraint annotations (`@TenantId`, `@ResourceId`, `@IdempotencyKey`, `@QueryText`, `@FreeText`, `@AllowHtml`) with composed-annotation tests | 6 annotations + tests | 0.5 day |
| SC4-5 | Implement `ValidationExceptionHandler` `@RestControllerAdvice`; add `synapt.validation.strict` config seam | Handler + config binding | 0.5 day |
| SC4-6 | Implement `PgvValidatingServerInterceptor` with `BadRequest` payload + metric emission | Interceptor + unit test | 1 day |
| SC4-7 | Ship `PgvValidatingConfiguration` auto-config; smoke test against a demo `.proto` with a bad field | Config + integration test | 0.5 day |
| SC4-8 | Ship `SanitizerTestKit` + `PgvViolationAssertions` as `testFixtures` | Test fixtures | 0.5 day |
| SC4-9 | Component integration test: minimal Spring Boot app with `SanitizingModule` receives `<script>` payload → controller sees escaped text; metric increments | `SanitizerIntegrationTest` | 1 day |

---

## 8. Testing Strategy

- **Unit:** `SanitizingStringDeserializerTest` with the OWASP evasion payload corpus (script tags, `javascript:` URIs, unicode overlong sequences, mixed case, CDATA). `PgvValidatingServerInterceptorTest` asserts `Status.INVALID_ARGUMENT` and `BadRequest` details for representative rule failures.
- **Component:** `SanitizerIntegrationTest` spins a minimal `@SpringBootTest` app, POSTs XSS payloads to a controller, asserts (a) controller receives sanitised text, (b) metric is `+1`. `PgvGrpcTest` uses `InProcessServerBuilder`, sends a message that violates `string.pattern`, asserts field violation surfaces.
- **Regression:** All Phase 3 `shared/common` tests (Kafka wrapper, MDC) run unchanged.
- **Fuzz (feeds Phase 4 DoD §12.3):** `SanitizerFuzzTest` in `synapt` (not here) reuses `SanitizerTestKit` with 10K variants.

---

## 9. Configuration Surface (published defaults; consumed by downstream services)

```yaml
# shared-common defaults applied when downstream services install the module
synanton.sanitizer:
  enabled: true
  allowed-tags: [b, i, em, strong, code, pre, blockquote, ul, ol, li, p, br]
  allowed-attributes: [href, title]
  strip-unsafe-css: true
  allow-relative-links: false

synapt.validation:
  strict: false                      # false = warn+accept (default Phase 4); true = reject
  max-string-length-hard-cap: 65536  # absolute ceiling even without @Size

grpc.validation:
  enabled: true
```

The `synapt.validation.strict` default of `false` matches the design-doc rollout stance in §24 (v1.18): Phase 4 flips it to `true` per tenant after a two-week soak in warn mode. Per-tenant flip is done via `topology` (see `10-topology.md`).

---

## 10. Risks and Open Questions

| Risk | Mitigation | Decision |
|---|---|---|
| OWASP sanitiser dependency size (~1.5 MB) inflates every service image | Accept - already gated by Spring Boot base image size (~200 MB); library is negligible | Accepted |
| `@AllowHtml` becomes a bypass magnet | (1) Grep-fail CI gate: `@AllowHtml` occurrences must appear in an allow-list file `shared/common/allow-html-fields.txt` reviewed by security. (2) Metric `synapt_sanitization_skipped_total` alerted if it grows unexpectedly. | Add CI gate in Task SC4-8 |
| PGV runtime library missing when service publishes stale proto | Interceptor logs `WARN pgv-not-generated` at boot and permits calls (does not fail closed) - matches design doc's rollout stance | Warn-then-fail-closed switch is `grpc.validation.strict` (Phase 5) |
| Deserialiser adds latency on every string field | Sanitiser is O(n) on input size and idempotent on safe text; benchmark shows p99 < 200 µs per string ≤ 1 KB. Cache internally at `PolicyFactory` singleton. | Accepted |
| Constraint annotations bind services to `shared/common` version compatibility | Follows N-2 schema-migration discipline from §42; annotations never removed within N-2 window | Accepted |

---

## 11. Definition of Done (Phase 4)

1. Adding `shared/common:4.0.0` + one `@Bean Jackson2ObjectMapperBuilderCustomizer` to a Spring Boot service is sufficient to enable OWASP sanitisation on all `String` fields except those annotated `@AllowHtml`.
2. `SanitizerFuzzTest` (in `synapt`) shipping 10K OWASP evasion payloads finds zero bypass.
3. `synapt_sanitization_applied_total`, `synapt_sanitization_skipped_total`, `synapt_validation_rejected_total`, `synapt_validation_lenient_warning_total`, `grpc_validation_failed_total` metrics are emitted with the exact label sets in `synanton-design-1.19.md` §45.
4. `ValidationExceptionHandler` returns `RFC 7807` problem+json with `field_errors[]` array; snapshot test locks the shape.
5. `PgvValidatingServerInterceptor` shipped and used by at least one gRPC service (`topology`, per `10-topology.md`) with a passing `PgvGrpcTest`.
6. Canonical constraints (`@TenantId`, `@ResourceId`, `@IdempotencyKey`, `@QueryText`, `@FreeText`) referenced by at least three downstream services.
7. CI gate `allow-html-fields-gate` fails if a new `@AllowHtml` occurrence appears outside the allow-list file.
8. Regression: all Phase 3 `shared/common` tests still pass.

---

## 12. Follow-on Phases (Signposted)

- **Phase 5** - Flip `grpc.validation.strict: true` platform-wide; remove warn mode.
- **Phase 5** - Support per-tenant sanitiser policy overrides via `topology.tenant_policy.security_sanitizer_overrides`.
- **Phase 5** - Publish TypeScript twin of the constraint schema for `synanton-mcp` and future first-party UIs (Zod schemas generated from the annotation set).
