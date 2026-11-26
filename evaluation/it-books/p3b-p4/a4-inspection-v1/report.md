# A4.1 manual inspection — regex baseline

**Source:** A4 candidate export + A2 annotations.
**Mode:** read-only; no extraction, prompt reconstruction, or graph writes.
**Prompt/response availability:** not available for the regex path; no historical prompt was captured.

This is a diagnostic sample, not a gold-label change or a replacement for A4 metrics.

## Failure taxonomy

| Bucket | Count in this export | Interpretation |
|---|---:|---|
| Predicted mentions not matched to A2 | 64 | Candidate over-extraction, wrong span, or wrong type |
| A2 mentions not matched | 16 | Missed span or non-identical boundary |
| Exact-span A2 matches | 6 | Mention boundary agrees; type may still differ |
| Exact-span type mismatches | 4 | Detection worked but type assignment did not |
| Regex relation candidates | 3 | All are outside the A1 predicate vocabulary |

## False-positive mention sample

| Source | Span | Surface / type | Source context | A2 match |
|---|---|---|---|---|
| think-python-2e | 211:217 | `UTF-16` / `Identifier` | ...pt; original PDF: samples/it-books/books/think-python-2e.pdf --> <!-- UTF-16 conformance probe: 😀 --> # Think Python: How to Think Like a Compute... |  |
| think-python-2e | 95:100 | `2.4.1` / `Identifier` | ...ier: CC-BY-NC-3.0 --> <!-- Source: Think Python, 2nd Edition, Version 2.4.1, Allen B. Downey. --> <!-- Derived excerpt; original PDF: samples/it-... |  |
| think-python-2e | 36:40 | `NC-3` / `Identifier` | ...<!-- SPDX-License-Identifier: CC-BY-NC-3.0 --> <!-- Source: Think Python, 2nd Edition, Version 2.4.1, Allen B.... |  |
| computer-networks-6.1 | 33:37 | `BY-4` / `Identifier` | ...<!-- SPDX-License-Identifier: CC-BY-4.0 --> <!-- Source: Computer Networks: A Systems Approach, 6th Edition... |  |
| think-python-2e | 352:355 | `1.1` / `Identifier` | ...mputer Scientist  Author: Allen Downey  ## Chapter 1: The way of the program  ### 1.1 What is a program?  A program is a sequence of instructio... |  |
| think-python-2e | 481:484 | `7.6` / `Identifier` | ... of instructions that specifies how to perform a computation.  ## Chapter 7: Iteration  ### 7.6 Algorithms  Newton's method is an example of an... |  |
| computer-networks-6.1 | 117:120 | `6.1` / `Identifier` | ...- Source: Computer Networks: A Systems Approach, 6th Edition, Version 6.1, Peterson and Davie. --> <!-- Derived excerpt; original PDF: samples/... |  |
| computer-networks-6.1 | 240:243 | `6.1` / `Identifier` | ...iginal PDF: samples/it-books/books/computer-networks-systems-approach-6.1.pdf --> # Computer Networks: A Systems Approach  Authors: Larry Peter... |  |

## False-negative mention sample

| Source | Span | Surface / type | Source context | A2 match |
|---|---|---|---|---|
| think-python-2e | 244:296 | `Think Python: How to Think Like a Computer Scientist` / `Book` | ...s/books/think-python-2e.pdf --> <!-- UTF-16 conformance probe: 😀 --> # Think Python: How to Think Like a Computer Scientist  Author: Allen Downey  ## Chapter 1: The way of the program  ### 1.... | m-tp-book / Book |
| think-python-2e | 318:351 | `Chapter 1: The way of the program` / `Chapter` | ...ink Python: How to Think Like a Computer Scientist  Author: Allen Downey  ## Chapter 1: The way of the program  ### 1.1 What is a program?  A program is a sequence of instru... | m-tp-ch1 / Chapter |
| think-python-2e | 352:374 | `1.1 What is a program?` / `Section` | ...mputer Scientist  Author: Allen Downey  ## Chapter 1: The way of the program  ### 1.1 What is a program?  A program is a sequence of instructions that specifies h... | m-tp-s11 / Section |
| think-python-2e | 377:384 | `program` / `Concept` | ... Allen Downey  ## Chapter 1: The way of the program  ### 1.1 What is a program?  A program is a sequence of instructions that specifies how to perf... | m-tp-program / Concept |
| think-python-2e | 460:480 | `Chapter 7: Iteration` / `Chapter` | ...program is a sequence of instructions that specifies how to perform a computation.  ## Chapter 7: Iteration  ### 7.6 Algorithms  Newton's method is an example o... | m-tp-ch7 / Chapter |
| think-python-2e | 481:495 | `7.6 Algorithms` / `Section` | ... of instructions that specifies how to perform a computation.  ## Chapter 7: Iteration  ### 7.6 Algorithms  Newton's method is an example of an algorithm:... | m-tp-s76 / Section |
| think-python-2e | 496:511 | `Newton's method` / `Algorithm` | ...s that specifies how to perform a computation.  ## Chapter 7: Iteration  ### 7.6 Algorithms  Newton's method is an example of an algorithm: it is a mechani... | m-tp-newton / Algorithm |
| think-python-2e | 532:541 | `algorithm` / `Concept` | ...mputation.  ## Chapter 7: Iteration  ### 7.6 Algorithms  Newton's method is an example of an algorithm: it is a mechanical process for solving a cate... | m-tp-algorithm / Concept |

## Exact-span matches

| Source | Span | Surface / type | Source context | A2 match |
|---|---|---|---|---|
| think-python-2e | 305:317 | `Allen Downey` / `Concept` | ...: 😀 --> # Think Python: How to Think Like a Computer Scientist  Author: Allen Downey  ## Chapter 1: The way of the program  ### 1.1 What is a program? ... | m-tp-author / Author |
| computer-networks-6.1 | 57:74 | `Computer Networks` / `Concept` | ...<!-- SPDX-License-Identifier: CC-BY-4.0 --> <!-- Source: Computer Networks: A Systems Approach, 6th Edition, Version 6.1, Peterson and Davie. --... | m-cn-topic / Topic |
| computer-networks-6.1 | 299:313 | `Larry Peterson` / `Concept` | ...s-approach-6.1.pdf --> # Computer Networks: A Systems Approach  Authors: Larry Peterson and Bruce Davie  ## Chapter 1: Foundation  ### 1.3 Architecture  F... | m-cn-author-peterson / Author |
| computer-networks-6.1 | 318:329 | `Bruce Davie` / `Concept` | ...--> # Computer Networks: A Systems Approach  Authors: Larry Peterson and Bruce Davie  ## Chapter 1: Foundation  ### 1.3 Architecture  For starters, the... | m-cn-author-davie / Author |
| computer-networks-6.1 | 584:587 | `RRP` / `Concept` | ...ning the form and content of a protocol graph a network architecture.  The protocols RRP (Request/Reply Protocol) and MSP (Message Stream Proto... | m-cn-rrp / Concept |
| computer-networks-6.1 | 755:758 | `HHP` / `Concept` | ... types of process-to-process channels, and both depend on the Host-to-Host Protocol (HHP) which provides a host-to-host connectivity service.  ... | m-cn-hhp / Concept |

## Type mismatches on exact spans

| Source | Span | Surface / type | Source context | A2 match |
|---|---|---|---|---|
| think-python-2e | 305:317 | `Allen Downey` / `Concept` | ...: 😀 --> # Think Python: How to Think Like a Computer Scientist  Author: Allen Downey  ## Chapter 1: The way of the program  ### 1.1 What is a program? ... | m-tp-author / Author |
| computer-networks-6.1 | 57:74 | `Computer Networks` / `Concept` | ...<!-- SPDX-License-Identifier: CC-BY-4.0 --> <!-- Source: Computer Networks: A Systems Approach, 6th Edition, Version 6.1, Peterson and Davie. --... | m-cn-topic / Topic |
| computer-networks-6.1 | 299:313 | `Larry Peterson` / `Concept` | ...s-approach-6.1.pdf --> # Computer Networks: A Systems Approach  Authors: Larry Peterson and Bruce Davie  ## Chapter 1: Foundation  ### 1.3 Architecture  F... | m-cn-author-peterson / Author |
| computer-networks-6.1 | 318:329 | `Bruce Davie` / `Concept` | ...--> # Computer Networks: A Systems Approach  Authors: Larry Peterson and Bruce Davie  ## Chapter 1: Foundation  ### 1.3 Architecture  For starters, the... | m-cn-author-davie / Author |

## Relation candidates

| Source | Predicate | Subject → object | A5 | A2 judgment | Evidence |
|---|---|---|---|---|---|
| think-python-2e | `is_a` | `Algorithms Newton's` (Concept) → `Executing` (Concept) | UNKNOWN_PREDICATE | UNMATCHED | RESOLVED |
| think-python-2e | `is_a` | `Algorithms Newton's` (Concept) → `Executing` (Concept) | UNKNOWN_PREDICATE | UNMATCHED | RESOLVED |
| computer-networks-6.1 | `is_a` | `Architecture For` (Concept) → `We` (Concept) | UNKNOWN_PREDICATE | UNMATCHED | RESOLVED |

All relation candidates use `is_a`, which A5 rejects as an unknown predicate. They cannot be accepted graph edges regardless of span quality.

## Manual review template

| Candidate | Human assessment | Notes |
|---|---|---|
| Each sampled mention/relation | correct / incomplete / unsupported / incorrect / unresolved | Fill in after source review; do not edit A2 or A4 |

## What remains unavailable

- System/user prompt and hydrated contract for a historical run.
- Raw model response and Gateway request/execution IDs.
- A causal attribution from prompt construction to a specific prediction.

Those fields are explicitly not reconstructed. For LLM runs they require the protected interaction-trace workflow, not this regex diagnostic.
