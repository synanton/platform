# Synthetic Ingestion Benchmark v1

**Status:** Frozen source + gold manifest; ingestion not executed
**Ticket:** #936
**Source:** `source/distributed-systems-primer.md`
**Gold:** `gold/manifest.json`

The source is synthetic CC0-1.0 content. The gold manifest is fixture-local (`gold:` IDs),
pins the IT Books v1 ontology release, and is never supplied to an extractor.

## Validate

```bash
python3 tools/it-books-synthetic/validate_gold.py --manifest gold/manifest.json
python3 -m unittest discover -s tools/it-books-synthetic -p 'test_*.py'
./gradlew :java:synflux:test \
  --tests 'org.synanton.synflux.document.SyntheticFixtureValidationTest'
sha256sum -c SHA256SUMS
```

`SyntheticFixtureValidationTest` verifies that every gold span and evidence element matches the
P1 structural text.

## Boundaries

No extraction or ingestion was run. A2, A4/A4.1, A8/A8.1, ontology releases, graph state, CSO and
evaluation baselines are unchanged. The fixture is not part of real-book scores.

## First isolated run

See `results/run-v1/REPORT.md` and `results/run-v1/run-manifest.json`. The run is **PARTIAL**: the existing ingestion path produced 2 FALLBACK chunks vs 14 gold chunks; regex extraction produced 94 entity and 12 relation candidates. No production graph or semantic model was used.
