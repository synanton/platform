# Synanton Platform — Development Guide

## Build & Test

```bash
./gradlew compileJava                    # compile before changes; stop if it fails
./gradlew test                           # run all tests
./gradlew :java:<module>:test            # run tests for a specific module
./gradlew :java:<module>:test --tests '*'  # run a single test class
./gradlew buildAll                       # full build (compile + test + package)
```

- Java 21, Spring Boot 3.x, Gradle with Kotlin DSL
- Pre-change safety: run `./gradlew compileJava` before applying changes; stop if it fails

## Running Services

```bash
# Full demo via Docker Compose
./scripts/run-demo.sh

# Individual service
./gradlew :java:<module>:bootRun
```

## Project Structure

- `java/<module>/` — Spring Boot services; see `settings.gradle.kts` for the active module list
- `java/synanton-mcp/` — MCP protocol bridge
- `java/shared/common/` — shared utilities (TenantContext, etc.)
- `docs/architecture/decisions/` — ADRs (see ADRs section below for shape)
- `docs/implementation/platform-api-plane/` — Platform API implementation plan and M0 gate (single source of truth for blocker status)
- `docs/api/` — API reference (error model, OpenAPI specs)
- `deployment/docker/` — Docker Compose files per service
- `scripts/` — run and benchmark scripts
- `tests/integration/` — integration test scripts

## Code Style

- Hexagonal architecture: `adapter/in/`, `adapter/out/`, `domain/`, `config/`
- Lombok `@RequiredArgsConstructor` for DI; `final` fields
- Max line length: 120 characters
- No single-char variable names
- JSON parsing: Jackson `ObjectMapper` only — never `indexOf`/`substring`/regex
- `@Nullable` (`org.jspecify.annotations.Nullable`) on nullable fields/parameters; skip protobuf fields and test code
- No `@Value("${key:DEFAULT}")` — put defaults in `application.yml`, inject resolved values only
- Method/member order: static → fields → constructors → methods (public → protected → package → private)
- Multi-line builder patterns over one-liners

## Testing

- Unit tests: `{ClassName}Test.java`, no Spring context, Mockito for isolation
- Integration tests: Spring context, `integration/` package, black-box via API calls
- Assertions: whole-object comparison (`isEqualTo`, `containsExactly`), not field-by-field
- Test naming: generated names start with `"should"`
- Use `Instant.now(clock)` not `Instant.now()`; `clock` must be a `Clock.fixed(...)` bean
- Static imports for Mockito: `import static org.mockito.Mockito.*`

## ADRs

Location: `docs/architecture/decisions/adr-NNN-title.md`

Shape (shared by ADR-014 through ADR-019 — the live precedent):

```
# ADR-NNN: [Decision] — [Outcome]

**Status:** Proposed | Accepted | Superseded | Deprecated
**Basis:** [what motivates the decision]

## Decision
1. [decision]
Consequences:
- **[label]:** [effect]

## Context
[why this decision, what it replaces]
```

- Title carries the decision; `**Status:**` and `**Basis:**` are inline fields (no frontmatter, no Owner)
- `## Decision` with `Consequences:` as flat bullets inside the same section
- `## Context` comes last (after Decision, not before)
- No Alternatives table, no Follow-ups table, no References section
- Proposed → Architecture sign-off → Accepted; dated inline corrections for Accepted ADRs

## Key Paths

| Path | Purpose |
|------|---------|
| `docs/implementation/platform-api-plane/INDEX.md` | M0 gate blockers (single source of truth) |
| `docs/implementation/platform-api-plane/M0-contract-freeze-declaration.md` | M0 freeze criteria and evidence |
| `java/synanton-mcp/MCP-COMPLIANCE.md` | MCP protocol compliance declaration |
| `docs/api/error-model.md` | Canonical RFC 9457 error model |
| `docs/api/rest/openapi-auth.yaml` | Authentication API spec (W4) |
| `docs/architecture/synanton-design-1.32.md` | Platform API design (Appendices A-H) |
