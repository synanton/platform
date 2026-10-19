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
- After changing `gradle/libs.versions.toml` (or any dependency), run with `--refresh-dependencies` — Gradle's configuration cache can otherwise serve a stale classpath and fail compilation with "cannot find symbol" for a dependency that resolves in `dependencies`. Add `--no-configuration-cache` when a compile/test discrepancy appears.

## Git Hooks

Runtime-data guard is checked in: run once per clone
\`git config core.hooksPath .githooks\`. It rejects staged artifacts that
belong to containers, not source (.sst/.wal, .minio.sys/, data-cg/,
slice-* fixture roots) — \`git commit --no-verify\` is the deliberate escape.

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
- `deployments/docker/` — Docker Compose files per service
- `scripts/` — run and benchmark scripts
- `tests/integration/` — integration test scripts

## Code Style

- Hexagonal architecture (`adapter/in/`, `adapter/out/`, `domain/`, `config/`) applies to the larger services (gateway, synapt, relix). Smaller modules like `synanton-mcp` use flat packages (`api/`, `tool/`, `filter/`) — match the module you're working in.
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
- Test inputs must be shapes production code actually receives. A test that
  builds a synthetic shape the real path never produces passes while the
  production path has a hole (seen with filters, guards, and fixture residue) —
  ask "would this input reach this code in a real request?" in review.
- Test naming: descriptive `method_condition_expected` (e.g. `toolsCall_emptyScopeSet_denied`, `allowlistMatchesExpectedSetExactly`)
- Use `Instant.now(clock)` not `Instant.now()`; `clock` must be a `Clock.fixed(...)` bean
- Static imports for Mockito: `import static org.mockito.Mockito.*`

## Security Invariants

- **No tenant fallback.** Missing/blank tenant → reject. Never default to a tenant. See ADR-016 and the propagation contract.
- **Tenant only from verified context.** Never read an inbound `X-Tenant*` header; never forward one downstream.
- **Fail closed on verification failure** — reject, don't degrade.
- Error responses conform to `docs/api/error-model.md`; `request_id` always present.
- MCP capabilities bypass is `GET /mcp` only; `POST /mcp` is business traffic.

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

## Branch Discipline

After `git checkout -b <branch> origin/main`, assert the base before
committing: `git log --oneline origin/main..HEAD` must show nothing. Extra
commits mean the checkout started from the wrong base (a stale ref or a
previous branch's tip) — rebuilding is cheap before the first push, expensive
after (force-push invalidates anyone who pulled, and dragged-in history
surfaces unrelated files in CI diffs).

## Trust bar vs done

A slice is **done** when its acceptance criteria pass. It is **trustworthy**
when someone who was not involved can verify it works, understand what it does
not do, and diagnose it when it breaks.

Before declaring any milestone complete, ask the five questions in
`docs/implementation/vertical-slice/trust-bar.md`. A milestone that passes its
criteria but fails one of the five is done, not trustworthy — say which.

When a test passes, ask: could this pass while the production code is broken?
Four tests in the slice's development passed against shapes production never
produces. That question found all four.

## Status Discipline

- A gate row and the artifact it approves carry the same status. When a row
  closes, flip its artifact in the same PR (`Draft` → `Frozen`, or
  `Draft` → `Approved`). A row reading `Closed` beside an artifact reading
  "subject of that row" is a contradiction, not a nuance — it happened with
  W2 after gate row 5.
- Conversely, a code PR closes code; a docs PR closes rows. Keep the window
  between them short — rows went stale three times (2f, row 3, 4b) because
  the docs flip trailed the merge.

## Key Paths

| Path | Purpose |
|------|---------|
| `docs/implementation/platform-api-plane/INDEX.md` | M0 gate blockers (single source of truth) |
| `docs/implementation/platform-api-plane/M0-contract-freeze-declaration.md` | M0 freeze criteria and evidence |
| `java/synanton-mcp/MCP-COMPLIANCE.md` | MCP protocol compliance declaration |
| `docs/api/error-model.md` | Canonical RFC 9457 error model |
| `docs/api/rest/openapi-auth.yaml` | Authentication API spec (W4) |
| `docs/architecture/synanton-design-1.32.md` | Platform API design (Appendices A-H) |
