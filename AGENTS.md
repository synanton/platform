# Agent Instructions

This repository's development conventions, architecture boundaries, build/test commands, security invariants, and review requirements are documented in [`CLAUDE.md`](CLAUDE.md). Read and follow those instructions before making changes; this file adds a small cross-agent reasoning guideline and does not replace them.

## Inventive problem solving

For non-trivial engineering problems—especially architectural trade-offs, recurring failures, competing requirements, or changes that appear to require accumulating exceptions—consider a TRIZ-inspired exploration before committing to the first plausible solution.

- Clarify the desired outcome, constraints, assumptions, and system invariants.
- Identify the underlying contradiction where one exists; distinguish a genuine contradiction from an ordinary preference or an unverified assumption.
- Inspect existing components, data, interfaces, and operational capabilities as resources to reuse or recombine.
- Use relevant inventive principles to generate materially different options; do not apply all 40 principles mechanically.
- Compare options against correctness, security, complexity, maintainability, performance, operability, and compatibility with the current architecture.
- Validate uncertain assumptions with focused prototypes, tests, benchmarks, or other evidence.
- Record important alternatives and the rationale for the decision in the appropriate design note or ADR.

This is an optional reasoning aid, not a mandatory checklist for routine tasks. Do not manufacture alternatives, add process overhead, or introduce complexity merely to appear inventive. Prefer the simplest evidence-supported solution that satisfies the requirements and preserves project invariants.

See [TRIZ-inspired engineering problem solving](docs/engineering/triz-problem-solving.md) for the practical method and Synanton-oriented examples.
