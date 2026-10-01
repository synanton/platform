# Synanton Developer Guide — Infrastructure Discipline Rules

**Status:** Seeded 2026-10-01 · **Owner:** Docs owner (TBD — Week 1 gate)
**Scope:** Cross-cutting implementation disciplines citable by PR reviewers.
Pending rules land here via DOC-D2.3 (parameterization discipline from YDB-041,
composition boundaries); pattern additions via DOC-D3.3.

Related: [Benchmark Runner + Vector Engine plan](./implementation/benchmark-runner-vector/INDEX.md)

---

## Cross-runtime boundaries are services, not JNI

Any non-JVM component (Rust, Python, Go, C++) integrated into the platform runs as an
external service communicating over a process or network boundary. Native-library embedding
via JNI, JNA, JNR-FFI, or Panama FFI is out of scope.

Why:

- JNI couples the build toolchain: every JVM build requires the native toolchain for every
  supported platform.
- Native code faults crash the JVM — no isolation, no graceful degradation.
- Cross-language memory management is a class of bugs without a boundary.
- The port architecture already provides a clean seam: `LexicalRetriever`,
  `VectorRetriever`, and `SynvaultStore` are transport-agnostic. An external service is just
  another adapter.

What to do instead:

- Expose the external component over HTTP, gRPC, or Unix socket.
- Implement the relevant port (`LexicalRetriever`, `VectorRetriever`) as a Java client.
- Document the process model (sidecar, sidecar-with-cluster, distributed service) in the
  deployment posture.

What to do if JNI seems necessary:

- Measure first. If the out-of-process option meets the latency target, use it.
- If it doesn't, escalate to an architecture review with the measurement. The decision to use
  JNI requires explicit approval, not a code-review side effect.

Origin: Tantivy integration decision, 2026-10-01. Tantivy and Quickwit integrate as services;
JNI was evaluated and rejected.
