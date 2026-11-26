# A5 — Independent IT Books validation audit

**Issue:** #893 Workstream A / A5
**Authority:** #894 intended domains/ranges
**Contract under test:** P0 IT Books v1 (`47fe38f8…`, digest `3a4a5edf…`)
**Status:** Audit complete; implementation defect fixed; no demo acceptance claim

## Independence rule

The expected matrix below is transcribed from #894 and `ontology-v1.md`, not generated from
the P0 compiler. If the compiled contract disagrees with #894, the test fails rather than
accepting the implementation as its own authority.

## Coverage matrix

| Predicate | Domain | Range | Valid example | Invalid example |
|---|---|---|---|---|
| `AUTHORED_BY` | Book | Author | Book → Author | Chapter → Author |
| `HAS_CHAPTER` | Book | Chapter | Book → Chapter | Book → Section |
| `HAS_SECTION` | Chapter | Section | Chapter → Section | Book → Section |
| `COVERS_TOPIC` | Book, Chapter, Section | Topic | Book → Topic | Author → Topic; Book → Concept |
| `DEFINES` | Chapter, Section | Concept, Definition | Chapter → Concept | Book → Concept |
| `USES` | Chapter, Section, Algorithm, DataStructure, Database, DistributedSystem | Algorithm, Concept, DataStructure, Database, DistributedSystem | Algorithm → DataStructure | Book → Algorithm |
| `DEPENDS_ON` | Algorithm, Concept, DataStructure, Database, DistributedSystem | same | Database → DistributedSystem | Book → Database; Database → Book |
| `PART_OF` | Chapter, Section, Topic | Book, Chapter, Section | Chapter → Book | Book → Chapter |

Entity vocabulary: `Book`, `Author`, `Chapter`, `Section`, `Algorithm`, `DataStructure`,
`Concept`, `Database`, `DistributedSystem`, `Topic`, `Definition`.

## Test coverage

`ItBooksA5ValidationTest` asserts:

1. the compiled contract has exactly the A1 entity set and predicate set;
2. every compiled subject/object expansion matches the #894 matrix independently;
3. all eight valid examples are accepted with the canonical predicate ID;
4. all ten invalid examples are rejected as `DOMAIN_RANGE`, not silently accepted;
5. an unknown predicate is rejected as `UNKNOWN_PREDICATE` and never becomes a candidate;
6. the immutable release ID/digest are the expected IT Books v1 identities.

## Defects found and fixed

The audit found two real boundary defects in `LlmExtractionAdapter`:

1. The adapter normalized an accepted predicate to lower case, then looked up domain/range
   against the contract's upper-case ID. Valid relations were rejected as `DOMAIN_RANGE`.
2. Accepted relations retained the normalized lower-case predicate instead of the contract's
   canonical ID, so accepted output did not satisfy the ontology's exact predicate vocabulary.

Fix: resolve the predicate case-insensitively against the compiled contract, then emit the
contract's canonical ID. Domain/range validation uses the same resolved definition.

## Verification

```bash
./gradlew :java:extraction-engine:test \
  --tests 'org.synanton.extraction.engine.contract.ItBooksA5ValidationTest' \
  --tests 'org.synanton.extraction.engine.contract.P0ContractHydrationTest'
```

Result: all 7 tests passed.

## Boundaries

This is an A5 validation-boundary audit. It does not run extraction, change A2, modify the
published ontology release, activate IT Books globally, write graph state, or claim demo
acceptance. A4 baseline completion and A7 GPU re-evaluation remain separate.
