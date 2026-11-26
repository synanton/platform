# A8.1 — Isolated resolution evaluation

**Run ID:** `it-books-a8-resolution-v1`
**Status:** Evaluation complete; no production integration or graph writes
**Inputs:** merged A4.1 candidate export + independent canonical catalog + independent identity judgments
**Results SHA-256:** `31c81034414047c5456276bc4cb507793f4686fd65b632db9fcca8ce571a21b3`
**Catalog digest:** `f79ac6715ae58b0d034f2c72aa1bb034da29bdb4c2deb061685f4a18afe389b8`

## Boundary

- A2 is mention/span gold, not canonical-identity gold. A2 mention IDs are **not** used as
  canonical IDs and are not used to construct the catalog.
- The canonical catalog (`catalog.json`) and identity judgments (`judgments.json`) are separate,
  versioned artifacts. The judgments are independently curated over selected A4.1 candidates.
- Every A4.1 candidate is resolved and reported. Unresolved candidates are not filtered out.
- No production graph write, canonical graph promotion, A2 change, A4 metric change, or ontology
  release change is included.

## Outcome counts

| Metric | Result |
|---|---:|
| Candidates | 70 |
| Resolved | 5 |
| Unresolved | 58 |
| Ambiguous | 0 |
| Type mismatch | 7 |
| Resolution coverage | 0.0714 |
| Ambiguity rate | 0.0000 |
| Type-mismatch rate | 0.1000 |
| Reviewed candidates | 15 |
| Unjudged candidates | 55 |

## Reviewed-subset quality

Precision and recall are reported only against independently curated identity judgments; the
unjudged 55 candidates are included in coverage, not silently excluded.

| Metric | Result |
|---|---:|
| Reviewed candidates | 15 |
| Correct outcomes | 15 / 15 |
| Reviewed resolved | 5 |
| Correct resolved identities | 5 / 5 |
| Reviewed precision | 1.000 |
| Reviewed recall | 1.000 |
| Reviewed type mismatches | 5 |
| Reviewed ambiguities | 0 |

The five resolved identities are RRP, MSP, Message Stream Protocol, HHP, and Host-to-Host Protocol.
The five reviewed type mismatches are correct A8 refusals: A4.1 proposed `Concept` for book/author
targets. The five reviewed unresolved mentions include metadata, fragments, and a generic word.

## Interpretation

A8 v1 correctly preserves unresolved and type-mismatch outcomes rather than forcing identities.
The low coverage is expected: A4's regex candidates are dominated by non-knowledge fragments, and
the A4.1 inspection identified that separately. A8.1 does not repair extraction quality.

## Reproduce

```bash
./gradlew :java:synflux:a8ResolutionEvaluation \
  --args="--out evaluation/it-books/a8-v1/results"
cd evaluation/it-books/a8-v1/results && sha256sum -c SHA256SUMS
```

Two independent runs produced byte-identical results.

## Boundary reminder

This is not an A4 comparison and does not change A4's fixed baseline. A canonical-identity
resolver is now demonstrated on actual A4.1 candidates; pipeline integration and graph activation
remain separate, unauthorized work.
