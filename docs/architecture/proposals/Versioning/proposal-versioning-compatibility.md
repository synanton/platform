# Versioning & Compatibility Policy

**File:** `proposal-versioning-compatibility.md`
**Status:** Draft for Review
**Owner:** Synanton architecture group

## 1. Summary

EventLab, Benchmark Runner, manifest schemas, and UI evolve independently. This proposal defines independent semantic versioning and a compatibility matrix so historical benchmark results remain reproducible and renderable.

## 2. Independently Versioned Components

| Component            | Version example | Responsibility                 |
| -------------------- | --------------- | ------------------------------ |
| EventLab generator   | `2.1.0`         | Workload generation algorithms |
| Benchmark Schema     | `1.0.0`         | Benchmark Manifest structure   |
| Result Schema        | `1.4.0`         | Result Manifest structure      |
| Benchmark Runner     | `0.3.0`         | Execution and measurement      |
| Synanton UI Platform | `0.8.0`         | Rendering and interaction      |

## 3. Compatibility Matrix

| Benchmark Schema | Result Schema | EventLab Generator | Runner | UI Platform |
| ---------------- | ------------- | ------------------ | ------ | ----------- |
| 1.0.0            | 1.4.0         | 2.1.x              | 0.3.x  | 0.8.x       |
| 1.0.0            | 1.3.0         | 2.0.x              | 0.2.x  | 0.7.x       |

The UI must declare which Result Schema versions it can render.

## 4. Deprecation Policy

- Old schema versions remain published.
- Deprecated versions supported for at least N months after replacement.
- Breaking changes require a new major version.
- Generator algorithm changes create a new `algorithm_version`.

## 5. Open Questions

1. Who owns the compatibility matrix?
2. Where is it published?
3. How are schema migrations handled?
4. How long are old generator versions supported?

## 6. Review Checklist

- □  

  Independent versioning agreed.

- □  

  Compatibility matrix published.

- □  

  Deprecation policy defined.

- □  

  UI declares supported schema versions.

- □  

  Historical runs remain reproducible.