# A9.1 — Candidate-by-candidate diagnostic

**Source:** immutable A4.1 candidate export  
**Candidates:** 70  
**Relations:** 3  
**Mode:** diagnostic only; no extraction, ontology, A2, graph, or evaluator changes.

## Summary

| Primary category | Count |
|---|---:|
| `ENTITY_TYPE_ERROR` | 3 |
| `NONE_CORRECT` | 2 |
| `NONE_UNJUDGED` | 3 |
| `NON_KNOWLEDGE_TEXT` | 40 |
| `SPAN_GRANULARITY_ERROR` | 22 |

- In-comment candidates: **30**
- Matched correct: **2**
- Matched type mismatch: **4**
- Unjudged: **64**
- Unsupported relation candidates: **3**

## Candidate classification

| # | Source | Span | Surface | Predicted type | A2 | Judgment | Primary | Secondary | Root stage | Expected correction |
|---:|---|---|---|---|---|---|---|---|---|---|
| 1 | think-python-2e | 36:40 | `NC-3` | `Identifier` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 2 | think-python-2e | 95:100 | `2.4.1` | `Identifier` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 3 | think-python-2e | 211:217 | `UTF-16` | `Identifier` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 4 | think-python-2e | 5:28 | `SPDX-License-Identifier` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 5 | think-python-2e | 30:40 | `CC-BY-NC-3` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 6 | think-python-2e | 52:58 | `Source` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 7 | think-python-2e | 60:72 | `Think Python` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 8 | think-python-2e | 78:85 | `Edition` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 9 | think-python-2e | 87:94 | `Version` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 10 | think-python-2e | 102:109 | `Allen B` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 11 | think-python-2e | 111:117 | `Downey` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 12 | think-python-2e | 128:135 | `Derived` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 13 | think-python-2e | 154:157 | `PDF` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 14 | think-python-2e | 211:217 | `UTF-16` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 15 | think-python-2e | 244:256 | `Think Python` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | `MISSED_HEADING` | `SPAN_CONSTRUCTION` | Emit the complete H1/H2/H3 heading span; suppress nested heading fragments. |
| 16 | think-python-2e | 258:261 | `How` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `CANDIDATE_SELECTION` | Emit the complete H1/H2/H3 heading span; suppress nested heading fragments. |
| 17 | think-python-2e | 265:275 | `Think Like` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | `MISSED_HEADING` | `SPAN_CONSTRUCTION` | Emit the complete H1/H2/H3 heading span; suppress nested heading fragments. |
| 18 | think-python-2e | 278:303 | `Computer Scientist\nAuthor` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | `MISSED_HEADING` | `SPAN_CONSTRUCTION` | Emit the complete H1/H2/H3 heading span; suppress nested heading fragments. |
| 19 | think-python-2e | 278:296 | `Computer Scientist` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | `MISSED_HEADING` | `SPAN_CONSTRUCTION` | Emit the complete H1/H2/H3 heading span; suppress nested heading fragments. |
| 20 | think-python-2e | 287:303 | `Scientist\nAuthor` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | `MISSED_HEADING` | `SPAN_CONSTRUCTION` | Emit the complete H1/H2/H3 heading span; suppress nested heading fragments. |
| 21 | think-python-2e | 305:317 | `Allen Downey` | `Concept` | m-tp-author | MATCHED_TYPE_MISMATCH | `ENTITY_TYPE_ERROR` | `SPAN_GRANULARITY_ERROR` | `TYPING` | Use explicit author-line/structural cues; do not default to Concept. |
| 22 | think-python-2e | 318:325 | `Chapter` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | `MISSED_HEADING` | `SPAN_CONSTRUCTION` | Emit the complete H1/H2/H3 heading span; suppress nested heading fragments. |
| 23 | think-python-2e | 329:332 | `The` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `CANDIDATE_SELECTION` | Suppress stopwords/generic function words at candidate selection. |
| 24 | think-python-2e | 352:355 | `1.1` | `Identifier` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | `MISSED_HEADING` | `CANDIDATE_SELECTION` | Suppress bare numbering; retain it only as part of a complete heading span. |
| 25 | think-python-2e | 356:360 | `What` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `CANDIDATE_SELECTION` | Suppress stopwords/generic function words at candidate selection. |
| 26 | think-python-2e | 460:467 | `Chapter` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | `MISSED_HEADING` | `SPAN_CONSTRUCTION` | Emit the complete H1/H2/H3 heading span; suppress nested heading fragments. |
| 27 | think-python-2e | 471:480 | `Iteration` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | `MISSED_HEADING` | `SPAN_CONSTRUCTION` | Emit the complete H1/H2/H3 heading span; suppress nested heading fragments. |
| 28 | think-python-2e | 481:484 | `7.6` | `Identifier` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | `MISSED_HEADING` | `CANDIDATE_SELECTION` | Suppress bare numbering; retain it only as part of a complete heading span. |
| 29 | think-python-2e | 485:504 | `Algorithms\nNewton's` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | `MISSED_HEADING` | `SPAN_CONSTRUCTION` | Stop spans at line boundaries; do not join adjacent heading/body tokens. |
| 30 | think-python-2e | 645:654 | `Executing` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `CANDIDATE_SELECTION` | Suppress generic verbs/non-knowledge tokens. |
| 31 | computer-networks-6.1 | 33:37 | `BY-4` | `Identifier` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 32 | computer-networks-6.1 | 117:120 | `6.1` | `Identifier` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 33 | computer-networks-6.1 | 240:243 | `6.1` | `Identifier` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 34 | computer-networks-6.1 | 5:28 | `SPDX-License-Identifier` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 35 | computer-networks-6.1 | 30:37 | `CC-BY-4` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 36 | computer-networks-6.1 | 49:55 | `Source` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 37 | computer-networks-6.1 | 57:74 | `Computer Networks` | `Concept` | m-cn-topic | MATCHED_TYPE_MISMATCH | `NON_KNOWLEDGE_TEXT` | `ENTITY_TYPE_ERROR` | `STRUCTURAL_PARSING` | Review the A2/source ambiguity: the matched Topic span lies inside an HTML comment. Do not change A2; decide whether comment spans are in extraction scope before filtering. |
| 38 | computer-networks-6.1 | 76:94 | `A Systems Approach` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 39 | computer-networks-6.1 | 76:85 | `A Systems` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 40 | computer-networks-6.1 | 78:94 | `Systems Approach` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 41 | computer-networks-6.1 | 100:107 | `Edition` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 42 | computer-networks-6.1 | 109:116 | `Version` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 43 | computer-networks-6.1 | 122:130 | `Peterson` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 44 | computer-networks-6.1 | 135:140 | `Davie` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 45 | computer-networks-6.1 | 151:158 | `Derived` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 46 | computer-networks-6.1 | 177:180 | `PDF` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `STRUCTURAL_PARSING` | Exclude HTML comments and source/license metadata before candidate selection. |
| 47 | computer-networks-6.1 | 252:269 | `Computer Networks` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | `MISSED_HEADING` | `SPAN_CONSTRUCTION` | Emit the complete structural heading span; suppress nested/title fragments. |
| 48 | computer-networks-6.1 | 271:297 | `A Systems Approach\nAuthors` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | `MISSED_HEADING` | `SPAN_CONSTRUCTION` | Emit the complete structural heading span; suppress nested/title fragments. |
| 49 | computer-networks-6.1 | 271:280 | `A Systems` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | `MISSED_HEADING` | `SPAN_CONSTRUCTION` | Emit the complete structural heading span; suppress nested/title fragments. |
| 50 | computer-networks-6.1 | 273:289 | `Systems Approach` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | `MISSED_HEADING` | `SPAN_CONSTRUCTION` | Emit the complete structural heading span; suppress nested/title fragments. |
| 51 | computer-networks-6.1 | 281:297 | `Approach\nAuthors` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | `MISSED_HEADING` | `SPAN_CONSTRUCTION` | Emit the complete structural heading span; suppress nested/title fragments. |
| 52 | computer-networks-6.1 | 299:313 | `Larry Peterson` | `Concept` | m-cn-author-peterson | MATCHED_TYPE_MISMATCH | `ENTITY_TYPE_ERROR` | — | `TYPING` | Use the explicit Authors line/name cues; do not default to Concept. |
| 53 | computer-networks-6.1 | 318:329 | `Bruce Davie` | `Concept` | m-cn-author-davie | MATCHED_TYPE_MISMATCH | `ENTITY_TYPE_ERROR` | — | `TYPING` | Use the explicit Authors line/name cues; do not default to Concept. |
| 54 | computer-networks-6.1 | 330:337 | `Chapter` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | `MISSED_HEADING` | `SPAN_CONSTRUCTION` | Emit the complete structural heading span; suppress nested/title fragments. |
| 55 | computer-networks-6.1 | 341:351 | `Foundation` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | `MISSED_HEADING` | `SPAN_CONSTRUCTION` | Emit the complete structural heading span; suppress nested/title fragments. |
| 56 | computer-networks-6.1 | 352:355 | `1.3` | `Identifier` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | `MISSED_HEADING` | `CANDIDATE_SELECTION` | Emit the complete structural heading span; suppress nested/title fragments. |
| 57 | computer-networks-6.1 | 356:372 | `Architecture\nFor` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | `MISSED_HEADING` | `SPAN_CONSTRUCTION` | Emit the complete structural heading span; suppress nested/title fragments. |
| 58 | computer-networks-6.1 | 470:472 | `We` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `CANDIDATE_SELECTION` | Suppress generic function words and demonstratives. |
| 59 | computer-networks-6.1 | 570:573 | `The` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `CANDIDATE_SELECTION` | Suppress generic function words and demonstratives. |
| 60 | computer-networks-6.1 | 584:587 | `RRP` | `Concept` | m-cn-rrp | MATCHED_CORRECT | `NONE_CORRECT` | — | `NONE` | None; exact-span, type-correct candidate. |
| 61 | computer-networks-6.1 | 589:596 | `Request` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | — | `SPAN_CONSTRUCTION` | Merge acronym-expansion fragments into one candidate at the expansion span; do not emit partial phrases. |
| 62 | computer-networks-6.1 | 597:611 | `Reply Protocol` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | — | `SPAN_CONSTRUCTION` | Merge acronym-expansion fragments into one candidate at the expansion span; do not emit partial phrases. |
| 63 | computer-networks-6.1 | 617:620 | `MSP` | `Concept` | — | UNJUDGED | `NONE_UNJUDGED` | — | `NONE` | None; unjudged candidate. A2 provides no identity judgment for this form. |
| 64 | computer-networks-6.1 | 622:645 | `Message Stream Protocol` | `Concept` | — | UNJUDGED | `NONE_UNJUDGED` | — | `NONE` | None; unjudged candidate. A2 provides no identity judgment for this form. |
| 65 | computer-networks-6.1 | 622:636 | `Message Stream` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | — | `SPAN_CONSTRUCTION` | Merge acronym-expansion fragments into one candidate at the expansion span; do not emit partial phrases. |
| 66 | computer-networks-6.1 | 630:645 | `Stream Protocol` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | — | `SPAN_CONSTRUCTION` | Merge acronym-expansion fragments into one candidate at the expansion span; do not emit partial phrases. |
| 67 | computer-networks-6.1 | 732:753 | `Host-to-Host Protocol` | `Concept` | — | UNJUDGED | `NONE_UNJUDGED` | — | `NONE` | None; unjudged candidate. A2 provides no identity judgment for this form. |
| 68 | computer-networks-6.1 | 732:744 | `Host-to-Host` | `Concept` | — | UNJUDGED | `SPAN_GRANULARITY_ERROR` | — | `SPAN_CONSTRUCTION` | Merge acronym-expansion fragments into one candidate at the expansion span; do not emit partial phrases. |
| 69 | computer-networks-6.1 | 755:758 | `HHP` | `Concept` | m-cn-hhp | MATCHED_CORRECT | `NONE_CORRECT` | — | `NONE` | None; exact-span, type-correct candidate. |
| 70 | computer-networks-6.1 | 812:816 | `This` | `Concept` | — | UNJUDGED | `NON_KNOWLEDGE_TEXT` | — | `CANDIDATE_SELECTION` | Suppress generic function words and demonstratives. |

## Relation candidates

| Source | Predicate | Endpoints | A5 | Root stage | Expected correction |
|---|---|---|---|---|---|
| think-python-2e | `is_a` | `Algorithms\nNewton's` (Concept) → `Executing` (Concept) | UNKNOWN_PREDICATE | `RELATION_EXTRACTION` | Omit unless an A1 predicate and explicit textual evidence both support the relation. |
| think-python-2e | `is_a` | `Algorithms\nNewton's` (Concept) → `Executing` (Concept) | UNKNOWN_PREDICATE | `RELATION_EXTRACTION` | Omit unless an A1 predicate and explicit textual evidence both support the relation. |
| computer-networks-6.1 | `is_a` | `Architecture\nFor` (Concept) → `We` (Concept) | UNKNOWN_PREDICATE | `RELATION_EXTRACTION` | Omit unless an A1 predicate and explicit textual evidence both support the relation. |
