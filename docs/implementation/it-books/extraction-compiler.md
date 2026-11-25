# IT Books extraction-contract compiler (P0)

**Workstream:** P0 (#902) · **Accepted architecture:** #900 / `0098f84b`
**Release:** IT Books v1, `releaseId=47fe38f8-9f72-481b-826a-6c5b12a6027a`,
`releaseDigest=3a4a5edf876792ac92fa63494bd7181e4d8eb3826fb70e6fe4112d796be3831c`

The compiler is deterministic and LLM-free. It reads the immutable release through the production
Syntology APIs, compiles the stored IR into an extraction SPEC and template, and emits a construct
coverage report.

## Read path

```text
GET /v1/tenants/{tenant}/ontology-releases/{releaseId}
GET /api/v1/admin/ontology/schemas/{version}
```

The compiler does not read the Syntology database or Jena store directly, and it does not use the
active-ontology alias. It addresses the immutable release by `releaseId`, verifies the caller's
pinned `releaseDigest`, and then fetches the stored IR by the release's version. Switching the
cluster's active ontology does not change the compiler output.

## Canonicalization specification

This is the complete P0 digest contract, not a generic JSON claim.

- **Serialization:** JSON object keys are sorted lexicographically at every level; arrays preserve
  documented order; encoding is UTF-8; no insignificant whitespace; lower-case SHA-256.
- **Digest inputs:** SPEC and template are digested as complete canonical JSON documents. The
  report is provenance/coverage evidence and is not itself an input to `specDigest` or
  `templateDigest`.
- **Compiler identity:** `compilerVersion`, `exportProfile`, `schemaVersion`,
  `ontologyReleaseDigest`, and `irDigest` are all present in the canonical input set.
  `compilationId = sha256(releaseDigest | compilerVersion | exportProfile | schemaVersion |
  irDigest)`.
- **Floats:** P0 has no floating-point values. Future bbox-bearing artifacts must not use native
  JSON numbers: encode finite values as decimal strings with a fixed, declared scale and explicit
  coordinate system/units. NaN/Infinity are rejected.
- **Nondeterministic metadata:** timestamps, hostnames, absolute paths, tokens, and the deployed
  image are excluded from SPEC/template digests. The deployed image is recorded only in the report
  provenance. This is why two runs against the same release are byte-identical even if a deployment
  changes.
- **IR input:** `irDigest` covers the canonical IR JSON returned by the production API. The release
  digest remains the authoritative ontology identity.

This format is versioned by `json-v1:sorted-keys,utf8,no-whitespace,sha256`.

## Release digest canonicalization

`releaseDigest` is the existing #866/#868 Syntology digest:

```text
ontology-release-v1
format=hcl
component.schema_ir=sha256:<canonical IR JSON bytes>
component.shapes=sha256:<compiled SHACL Turtle bytes>
component.tbox=sha256:<compiled OWL Turtle bytes>
```

Component lines are sorted by name; the manifest is UTF-8; `releaseDigest` is lowercase SHA-256 of
the manifest. The compiler consumes this value and does not recompute or reinterpret it.

## Compiler digest canonicalization

Compiler outputs use `json-v1`: recursively sorted object keys, array order preserved, UTF-8, no
whitespace, lowercase SHA-256.

- `irDigest = sha256(canonical API IR)`
- `specDigest = sha256(canonical SPEC)`
- `templateDigest = sha256(canonical template)`
- `compilationId = sha256(releaseDigest | compilerVersion | exportProfile | schemaVersion | irDigest)`

`deployedImage` is recorded as provenance but is not a semantic compilation input. No floats are
present in P0 SPEC/template outputs.

## Coverage and support

For IT Books v1 the report classifies **all** constructs:

| Construct | Count |
|---|---:|
| Classes total | 20 |
| Support/infrastructure classes | 9 |
| Extraction classes | 11 |
| Representable classes | 11 |
| Relations total | 8 |
| Representable relations | 3 |
| Adapted/marker-expanded relations | 5 |
| Unsupported constructs | 0 |

Marker classes (`Thing`, `TopicBearing`, `TextDivision`, `DefinedConcept`, `UsesSubject`,
`UsesObject`, `DependencyNode`, `PartOfSubject`, `PartOfObject`) remain support types and are not
extraction targets. Relations whose domain/range is a marker are expanded to concrete extraction
types; that expansion is visible in the report and template. Unsupported datatypes/empty
domain-ranges are reported, never silently dropped.

## Marker adaptations

The five adapted relations are still first-class triples. "Marker-adapted" means the ontology
declares the domain/range with a support marker class; the compiler expands that marker to the
concrete extraction classes that inherit it. It is not an annotation, classifier, or hidden
shortcut.

| Predicate | Ontology domain/range | Concrete subjects | Concrete objects |
|---|---|---|---|
| `COVERS_TOPIC` | `TopicBearing` / `Topic` | Book, Chapter, Section | Topic |
| `DEFINES` | `TextDivision` / `DefinedConcept` | Chapter, Section | Concept, Definition |
| `USES` | `UsesSubject` / `UsesObject` | Chapter, Section, Algorithm, DataStructure, Concept, Database, DistributedSystem | Algorithm, DataStructure, Concept, Database, DistributedSystem |
| `DEPENDS_ON` | `DependencyNode` / `DependencyNode` | Algorithm, DataStructure, Concept, Database, DistributedSystem | Algorithm, DataStructure, Concept, Database, DistributedSystem |
| `PART_OF` | `PartOfSubject` / `PartOfObject` | Chapter, Section, Topic | Book, Chapter, Section |

The construct report records these exact expansions. Semantics are preserved by type inheritance:
the marker exists to encode a union without widening the predicate to an unconstrained type.

## Reproducible run

From a clean checkout, with a token and the pinned release identity:

```bash
SYNANTON_TOKEN="$SYNTOLOGY_TOKEN" \
RELEASE_ID=47fe38f8-9f72-481b-826a-6c5b12a6027a \
RELEASE_DIGEST=3a4a5edf876792ac92fa63494bd7181e4d8eb3826fb70e6fe4112d796be3831c \
./scripts/it-books/compile-release.sh
```

The wrapper invokes the same Gradle CLI. The committed fixture test
`./gradlew :java:extraction-compiler:test` independently reproduces the same digests without
network access.

The first run against the published release produced:

- `specDigest`: `8834d45a276792babbdf59ff6da43bf11ba5a8bdd43ca9755cfb159559694455`
- `templateDigest`: `333a900292eebabda75b4ed67ec909ecc5db450702e05ae23d108ed2cf2fb37b`
- `compilationId`: `cea21d1a4cebf9e45e2e6ebba9ae4c3899dfc336b2b098a959db6dd5a06f7e0b`

A second identical run produced byte-identical outputs and the same digests. Committed artifacts
are under `docs/implementation/it-books/p0/`.

## Boundaries

- No LLM in compile or gap-fill path.
- No extraction execution, prompt tuning, GPU, UI, canonical graph, or promotion.
- No Docling runtime or model dependency.
- No F1/gold/gate/profile/ADR-044 change.
