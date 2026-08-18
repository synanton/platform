# 08 - synapt - Phase 4 - Global JSON Sanitisation, Jakarta Validation Strict Mode, CSP + Companion Headers, Deprecation Policy

**Version:** 1.0
**Date:** 2026-08-11
**Status:** Draft for review
**Depends on:** Phase 3 `synapt` DoD (per-tenant rate limiting, budget enforcement, Kafka enqueue). Phase 4 `shared/common` (Phase 4 `01-shared-common.md`), `security` (`support_admin` role).
**Scope:** Wire the OWASP JSON sanitiser at the request `ObjectMapper` layer, ship canonical Jakarta Validation constraints on all public DTOs, publish the full v1.18 CSP + companion header set on every response, and implement the API deprecation machinery (`Warning: 299`, usage counters, N-2 removal gate).

---

## 1. Context and Document Alignment

| Source | Role in this plan |
|--------|-------------------|
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §24 `synapt` | Production target |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §24.1 Global JSON sanitisation *(v1.18)* | Wiring plan for the sanitiser primitive from `01-shared-common.md` |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §24 Jakarta Validation on public DTOs *(v1.18)* | Constraint annotations on every public DTO |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §49 Infrastructure Security Headers *(v1.18)* | CSP + companion headers |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §24 API deprecation policy *(v1.17)* | Deprecation machinery |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §24 Internal admin routes *(v1.19)* | `/admin/_internal/*` behind `support_admin` |
| [01-shared-common.md](./01-shared-common.md) | Primitives this plan wires up |

**Explicit non-goals for Phase 4:**

- No new endpoints beyond internal admin routes; existing REST surface unchanged.
- No response-body sanitisation (design decision - responses are trusted server-generated; only inbound bodies are sanitised).
- No breaking removal of deprecated fields (Phase 5+; Phase 4 lands the machinery only).

---

## 2. Phase 4 in One Sentence

> Every JSON body entering synapt is sanitised at the Jackson layer, validated by Jakarta constraints, then answered with a browser-security-headered response - and every deprecated field emits a `Warning: 299` header plus a usage counter so a future release can remove it safely.

---

## 3. Target Architecture

```mermaid
flowchart LR
  CLIENT[HTTP POST /search] --> HDR_IN[HeaderCheck HSTS negotiation]
  HDR_IN --> JACKSON[ObjectMapper + SanitizingModule]
  JACKSON --> VAL[Jakarta Validation on DTO]
  VAL -->|fail strict| BAD[400 field_errors]
  VAL -->|pass| CTRL[Controller]
  CTRL --> DOWN[downstream services]
  DOWN --> RESP[Response body]
  RESP --> WARN[DeprecationWarningFilter adds Warning:299]
  WARN --> HDR_OUT[SecurityHeadersFilter adds CSP + companions]
  HDR_OUT --> CLIENT
  ADMIN[/admin/_internal/*] --> SUPPORT[SupportAdminAuthzFilter]
  SUPPORT --> CTRL
```

---

## 4. Data Contracts

### 4.1 Request/response headers (added in Phase 4)

All API responses (JSON and browser HTML):

```
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; font-src 'self'; base-uri 'self'; object-src 'none'; form-action 'self'; frame-ancestors 'none'; require-trusted-types-for 'script'; report-uri /csp-report; report-to csp-endpoint
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: camera=(), microphone=(), geolocation=(), interest-cohort=()
Strict-Transport-Security: max-age=31536000; includeSubDomains; preload
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Resource-Policy: same-origin
```

For deprecated field usage:

```
Warning: 299 - "field 'foo' is deprecated since 1.17; use 'bar'; removal earliest 1.20"
```

### 4.2 `POST /csp-report` endpoint

Accepts browser CSP violation reports:

```json
{
  "csp-report": {
    "document-uri": "https://synanton.example/",
    "violated-directive": "script-src 'self'",
    "blocked-uri": "https://evil.example/x.js",
    "source-file": "https://synanton.example/app.js",
    "line-number": 42
  }
}
```

Response: 204 No Content. Increments `ui_csp_violation_report_total{directive,blocked_uri}` metric. Rate-limited to 10 rps per client IP.

### 4.3 `/admin/_internal/*` routes (v1.19 additions)

| Route | Method | Purpose |
|---|---|---|
| `/admin/_internal/status` | GET | Cluster health snapshot |
| `/admin/_internal/bundle` | POST | Support bundle generation |
| `/admin/_internal/clean` | POST | Cache cleanup (tenant / cache resource) |
| `/admin/_internal/delete` | POST | Destructive content/tenant delete (requires `confirm: I_AM_SURE`) |
| `/admin/_internal/recrawl` | POST | Recrawl trigger |
| `/admin/_internal/recrawl/{tenant}` | GET | Recrawl status |
| `/admin/_internal/workflow/cancel` | POST | Cancel Temporal workflow |
| `/admin/_internal/workflow/retry` | POST | Retry Temporal workflow |

All gated by: (1) `Authorization: Bearer <SYNANTON_SUPPORT_KEY>`, (2) `support_admin` role, (3) `synapt.admin.internal.allowed_cidrs[]`.

---

## 5. Implementation Design

### 5.1 Sanitiser wiring

```java
@Configuration
class SanitizerConfig {
    @Bean SanitizingModule sanitizingModule(HtmlSanitizerPolicyFactory factory,
                                            @Value("${synapt.sanitizer.enabled}") boolean enabled) {
        return enabled ? new SanitizingModule(factory) : new SimpleModule();
    }
    @Bean Jackson2ObjectMapperBuilderCustomizer customizer(SanitizingModule module) {
        return b -> b.modules(module);
    }
}
```

`HtmlSanitizerPolicyFactory` (from `shared/common`) reads `synanton.sanitizer.*` keys, produces a `PolicyFactory` from `com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20240325.1`.

### 5.2 Jakarta Validation

Every DTO annotated with canonical constraints from `shared/common` (see `01-shared-common.md`):

```java
public record SearchRequest(
    @TenantId String tenantId,
    @QueryText String query,
    @Min(1) @Max(1000) int topN,
    @Size(max = 64) List<@Pattern(regexp = "^[a-zA-Z0-9_-]{1,64}$") String> filters
) {}
```

Feature flag: `synapt.validation.strict=false` (default in Phase 4) → warn+accept; `true` → reject. Rollout plan: two-week soak with `strict=false`, monitor `synapt_validation_lenient_warning_total`, then flip per tenant.

Global `@RestControllerAdvice`: `ValidationExceptionHandler` (from `shared/common`) translates `MethodArgumentNotValidException` to `400 field_errors[]`.

### 5.3 `SecurityHeadersFilter`

`WebFilter` (reactive) or `OncePerRequestFilter` (MVC) applied to *every* response - even error paths. Header values driven by config:

```java
@Component
class SecurityHeadersFilter extends OncePerRequestFilter {
    protected void doFilterInternal(...) {
        response.setHeader("Content-Security-Policy", cspBuilder.build(mode));  // enforce | report_only
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        // ... etc
    }
}
```

CSP mode config: `ui.security.csp.mode: enforce|report_only`. Rollout: Day 1 = `report_only` in canary for 2 weeks, then `enforce`.

Companion headers: `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, `Permissions-Policy`, `HSTS`, `COOP`, `CORP` per §49.

### 5.4 `CspReportController`

```java
@RestController
class CspReportController {
    @PostMapping("/csp-report")
    ResponseEntity<Void> report(@RequestBody CspReport report) {
        var directive = report.getViolatedDirective();
        var blocked = redactUri(report.getBlockedUri());
        metrics.increment("ui_csp_violation_report_total", "directive", directive, "blocked_uri", blocked);
        return ResponseEntity.noContent().build();
    }
}
```

Rate-limited via Spring's `RateLimiterAspect` (already in Phase 3): 10 rps / IP.

### 5.5 Deprecation machinery

`@Deprecated` marker on DTO fields:

```java
public record SearchRequest(
    @TenantId String tenantId,
    @QueryText String query,
    @DeprecatedField(since = "1.17", replacement = "top_n", removalEarliest = "1.20")
    Integer topResults      // deprecated alias for top_n
) {}
```

`DeprecationWarningFilter` scans response bodies (via `MappingJackson2HttpMessageConverter` interceptor) and request DTOs (via `@RequestBody` post-binding hook) for deprecated fields:

- Response: adds `Warning: 299 - "field 'top_results' is deprecated since 1.17..."` header per field.
- Request: increments `synapt_deprecated_field_usage_total{tenant,field,since}`.

CI gate `deprecation-gate` (GitHub Action):

```yaml
name: deprecation-gate
on: pull_request
jobs:
  check:
    steps:
      - name: query Prometheus for deprecated field usage
        run: |
          for field in $(grep -r "@DeprecatedField" --include="*.java" | ...); do
            usage = curl "$PROM/api/v1/query?query=sum(rate(synapt_deprecated_field_usage_total{field=$field}[30d]))"
            if [ "$usage" != "0" ]; then
              echo "Cannot remove $field: still used"; exit 1
            fi
          done
```

Removal is allowed only when usage counter is `0` for ≥ 30 days.

**Never-deprecated surfaces (per §24 v1.17):** `tenant_id`, `content_ref_id`, `chunk_id`, `entity_id`, `execution_trace.warnings`, residency constraints. Enforced by a linter checking `@DeprecatedField` annotations do not target these names.

### 5.6 Internal admin routes

`SupportAdminAuthzFilter` (before `SanitizingModule`, before Controllers):

```java
class SupportAdminAuthzFilter extends OncePerRequestFilter {
    protected void doFilterInternal(...) {
        if (!request.getRequestURI().startsWith("/admin/_internal/")) { chain.doFilter(); return; }
        if (!inAllowedCidr(request.getRemoteAddr(), config.allowedCidrs)) { deny(403, "cidr_not_allowed"); return; }
        var subject = supportKeyValidator.validate(request.getHeader("Authorization"));
        if (!subject.hasRole("support_admin")) { deny(403, "role_missing"); return; }
        request.setAttribute("subject", subject);
        chain.doFilter();
    }
}
```

Destructive endpoints check request body `confirm == "I_AM_SURE"` before executing.

Metrics: `helper_operation_total{command,tenant,outcome}`, `helper_auth_failure_total{reason}`, `helper_destructive_ops_total{command,tenant}`. Alerts `HelperDestructiveOpsRate` (page if > 10 in 15 min), `HelperAuthFailureSpike` (page if > 20 in 5 min for `invalid_key` or `wrong_role`).

Every call writes an `admin_audit` row (see `10-topology.md` for schema) with `actor_type=support_admin`, `before_state_hash`, `after_state_hash`.

---

## 6. Module Boundaries

| Module | Owns in Phase 4 | Does not own |
|---|---|---|
| `synapt` | Sanitiser wiring, Jakarta constraint DTO placement, `SecurityHeadersFilter`, `CspReportController`, `DeprecationWarningFilter`, `SupportAdminAuthzFilter`, deprecation-gate CI job | The sanitiser deserialiser itself (in `shared/common`); the `support_admin` role definition (in `security`); admin_audit schema (in `topology`) |
| `shared/common` | Sanitiser + PGV + canonical constraint annotations | Wiring to specific routes |
| `security` | `support_admin` role, `SYNANTON_SUPPORT_KEY` validation | Admin route filtering |
| `topology` | `admin_audit` schema + write API | Writing rows (synapt writes) |
| `control-plane` | Also exposes `/admin/_internal/*` on its own port (for admin-only endpoints not proxied through synapt) | synapt's copy |

---

## 7. Prerequisites

| # | Prereq | Home | Notes |
|---|---|---|---|
| 1 | `shared/common:4.0.0` published with `SanitizingModule`, canonical annotations, `ValidationExceptionHandler` | `01-shared-common.md` | Non-negotiable |
| 2 | `security` publishes `support_admin` role and `SYNANTON_SUPPORT_KEY` validation | `09-security.md` | Non-negotiable |
| 3 | `topology` publishes `admin_audit` schema with `before_state_hash`, `after_state_hash` | `10-topology.md` | Yes |
| 4 | Prometheus reachable from CI for `deprecation-gate` job | `15-observability.md` | Non-negotiable |
| 5 | `ui.security.csp.mode` config bound and defaulted to `report_only` for first two weeks | ops overlay | Rollout |

---

## 8. Task Breakdown

| # | Task | Deliverable | Est. |
|---|---|---|---|
| SA4-1 | Add `shared/common:4.0.0` dep bump; install `SanitizingModule` via customizer | Config change + smoke test | 0.5 day |
| SA4-2 | Annotate every public DTO (`SearchRequest`, `IngestRequest`, `GrantRequest`, etc.) with canonical constraints; strict-mode config seam | DTO edits + tests | 1.5 days |
| SA4-3 | Wire `ValidationExceptionHandler` from `shared/common`; snapshot test on RFC 7807 body shape | Snapshot test | 0.5 day |
| SA4-4 | Implement `SecurityHeadersFilter`; per-header config binding | Filter + tests | 1 day |
| SA4-5 | Implement `CspBuilder`; `enforce`/`report_only` modes; per-route override for the future admin UI | Class + tests | 0.5 day |
| SA4-6 | Implement `CspReportController` with rate limit + metric | Controller + tests | 0.5 day |
| SA4-7 | Introduce `@DeprecatedField(since, replacement, removalEarliest)` annotation and `DeprecationWarningFilter` | Annotation + filter + tests | 1.5 days |
| SA4-8 | Add `deprecation-gate` GitHub Action wired to Prometheus | Workflow YAML + docs | 1 day |
| SA4-9 | Implement `SupportAdminAuthzFilter`; CIDR allow-list + role check | Filter + tests | 1 day |
| SA4-10 | Implement `/admin/_internal/*` route handlers (proxy to control-plane where needed) | Controllers + tests | 1.5 days |
| SA4-11 | Enforce `confirm: I_AM_SURE` on destructive routes | Handler check + tests | 0.5 day |
| SA4-12 | Wire `admin_audit` row emission on every `/admin/_internal/*` call | Audit writer wiring | 0.5 day |
| SA4-13 | Metrics: `synapt_sanitization_applied_total`, `synapt_sanitization_skipped_total`, `synapt_validation_rejected_total`, `synapt_validation_lenient_warning_total`, `synapt_deprecated_field_usage_total`, `ui_csp_violation_report_total`, `helper_*_total` | Micrometer | 0.5 day |
| SA4-14 | Fuzz test `SanitizerFuzzIT` with 10K OWASP evasion payloads against `POST /search`; assert 0 bypass | `SanitizerFuzzIT` | 1 day |
| SA4-15 | Integration test `CspHeaderIT`: every response carries the full header set | `CspHeaderIT` | 0.5 day |
| SA4-16 | Integration test `DeprecationIT`: deprecated field usage → `Warning: 299` header + metric | `DeprecationIT` | 0.5 day |
| SA4-17 | Integration test `SupportAdminIT`: valid key + CIDR → 200; wrong CIDR → 403; missing confirm on destructive → 400 | `SupportAdminIT` | 0.5 day |

---

## 9. Testing Strategy

- **Unit:** CSP builder mode toggling. Deprecation warning format. Support admin authz filter (CIDR + role + confirm). Rate limit on CSP report endpoint.
- **Integration:** All `*IT` classes with WebMvc test slice.
- **Fuzz:** `SanitizerFuzzIT` uses `SanitizerTestKit` from `shared/common` with OWASP evasion payload corpus.
- **Regression:** Phase 3 rate-limit, budget-enforcement, Kafka enqueue tests unchanged.
- **Contract:** `csp-smoke-test` CI job (from `INDEX.md` DoD 6) renders each UI route via headless Chrome and asserts zero CSP violations. Runs against the *admin UI*, exercised in `14-syntology-admin.md`.

---

## 10. Configuration Surface

```yaml
# synapt/src/main/resources/application-phase4.yaml
synapt:
  sanitizer:
    enabled: true
    # allowed-tags / allowed-attributes inherited from shared/common defaults
  validation:
    strict: false
    max-string-length-hard-cap: 65536
  admin:
    internal:
      enabled: true
      allowed_cidrs:
        - "10.0.0.0/8"
        - "172.16.0.0/12"
  deprecation:
    warn_on_usage: true
    ci_removal_gate_prometheus_url: "http://prometheus.observability.svc:9090"

ui.security:
  csp:
    enabled: true
    mode: "report_only"   # flip to "enforce" after 2-week soak
    report_uri: "/csp-report"
  headers:
    hsts_max_age_seconds: 31536000
    frame_options: "DENY"
    referrer_policy: "strict-origin-when-cross-origin"
```

---

## 11. Risks and Open Questions

| Risk | Mitigation | Decision |
|---|---|---|
| CSP `report_only` mode masks legitimate bugs before flip | 2-week soak enforced by ops runbook; dashboard `ui_csp_violation_report_total` reviewed at end of week 1 | Runbook |
| Sanitiser breaks legitimate HTML in a webhook body | `@AllowHtml` marker + CI grep gate limits scope; webhook receivers must carry an explicit annotation | Gate |
| Strict-mode Jakarta Validation rejects legit legacy clients | Rollout per-tenant behind `topology.tenant_policy.validation_strict=true`; two-week soak per tenant | Per-tenant flip |
| `deprecation-gate` CI job flaky if Prometheus down | Job fails-open (allows merge) with WARN comment on PR; explicit alert `DeprecationGateProbeFailed` if Prom unreachable > 1 h | Fail-open |
| Support admin bypass by IP spoofing via reverse proxy | Reverse proxy MUST set `X-Forwarded-For` and synapt uses `RemoteIpValve` to derive real client IP; documented in deployment guide | Documented |
| CSP `style-src 'unsafe-inline'` is a compromise (§49) | Flagged for Phase 5 with a nonce-injection tightening; acceptable for Phase 4 given no known XSS surface | Deferred |

---

## 12. Definition of Done (Phase 4)

1. `SanitizerFuzzIT` with 10K OWASP payloads passes with 0 bypass.
2. Every response from every synapt route carries the full v1.18 header set (verified by `CspHeaderIT`).
3. `POST /csp-report` accepts a violation payload, returns 204, increments `ui_csp_violation_report_total`; rate limit 10 rps/IP enforced.
4. `@DeprecatedField` on any DTO field produces `Warning: 299` header on responses and `synapt_deprecated_field_usage_total` on requests.
5. `deprecation-gate` CI job present in `.github/workflows/`; fails on PRs that remove a still-used field.
6. `/admin/_internal/*` requires `SYNANTON_SUPPORT_KEY` + `support_admin` role + allowed CIDR; destructive routes require `confirm: I_AM_SURE`; every call writes an `admin_audit` row.
7. `synapt.validation.strict=false` in default profile; flipping to `true` per tenant rejects invalid inputs with `RFC 7807` body.
8. All Phase 3 rate-limit / budget / Kafka enqueue tests pass unchanged.

---

## 13. Follow-on Phases (Signposted)

- **Phase 5** - Flip `ui.security.csp.mode` to `enforce` platform-wide (after soak).
- **Phase 5** - Tighten CSP `style-src` to nonce-based; remove `'unsafe-inline'`.
- **Phase 5** - Flip `synapt.validation.strict=true` platform default; move `false` to opt-out.
- **Phase 5** - Remove first deprecated field that has met the 30-day zero-usage gate.
