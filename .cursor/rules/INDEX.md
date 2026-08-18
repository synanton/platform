# Synanton AI Rules - Index

Rules and skills for AI coding assistants (Cursor, Claude Code) working on the Synanton platform.

## Top-level rules (auto-applied by glob)

| File | Glob | Description |
|------|------|-------------|
| [`java-rules.mdc`](./java-rules.mdc) | `**/*.java` | Java code generation: hexagonal arch, Gradle build, Lombok, Spring Boot, testing conventions |
| [`proto-rules.mdc`](./proto-rules.mdc) | `**/*.proto` | Protobuf/gRPC: SPI contracts, PGV validation, naming, versioning, Gradle proto build |

## Skills (invoke by name)

### Gradle Build (Synanton-specific)

| Skill | Description |
|-------|-------------|
| `@110-java-gradle-best-practices` | Multi-module Gradle Kotlin-DSL setup, version catalog, convention plugins |
| `@111-java-gradle-dependencies` | Dependency scopes, BOM imports, conflict resolution, dependency tree inspection |
| `@112-java-gradle-plugins` | Spring Boot, protobuf/gRPC/PGV, checkstyle, JaCoCo, OWASP plugin configuration |

### Java Design

| Skill | Description |
|-------|-------------|
| `@121-java-object-oriented-design` | OO design principles, SOLID, design patterns |
| `@122-java-type-design` | Type system, records, sealed classes, enums |
| `@123-java-exception-handling` | Exception hierarchy, try-with-resources, error propagation |
| `@124-java-secure-coding` | Input validation, SQL injection prevention, XSS, secrets handling |
| `@125-java-concurrency` | Virtual threads, `java.util.concurrent`, bounded queues, `InterruptedException` |
| `@128-java-generics` | PECS, bounded type parameters, no raw types |

### Java Testing

| Skill | Description |
|-------|-------------|
| `@131-java-testing-unit-testing` | JUnit 5, AssertJ, Mockito, Given-When-Then, boundary conditions |
| `@132-java-testing-integration-testing` | Spring Boot test slices, Testcontainers, WireMock, black-box API testing |

### Java Modern Features

| Skill | Description |
|-------|-------------|
| `@141-java-refactoring-with-modern-features` | Records, sealed types, pattern matching, text blocks |
| `@142-java-functional-programming` | Streams, lambdas, method references, functional interfaces |
| `@143-java-functional-exception-handling` | Functional error handling, `Optional`, `Either`-style patterns |
| `@144-java-data-oriented-programming` | Data-oriented design with records and sealed hierarchies |

### Documentation and Architecture

| Skill | Description |
|-------|-------------|
| `@170-java-documentation` | Javadoc, module-level documentation, API reference |
| `@171-java-adr` | Architecture Decision Records format and process |
| `@172-java-diagrams` | Mermaid diagrams, sequence diagrams, architecture diagrams |
| `@173-java-agents` | AI agent integration patterns in Java |

## Project Context

- **Platform:** Synanton - polyglot, multi-tenant enterprise knowledge platform
- **Design doc:** `docs/architecture/synanton-design-1.19.md`
- **Build system:** Gradle with Kotlin DSL (`build.gradle.kts`)
- **Java version:** 21
- **Key frameworks:** Spring Boot 3.x, gRPC, Protobuf, Cassandra, PostgreSQL, Kafka, Redis
- **Architecture:** Hexagonal (ports and adapters)
- **Module layout:** `java/<module>/`, `rust/<module>/`, `tools/synanton-ops/`
