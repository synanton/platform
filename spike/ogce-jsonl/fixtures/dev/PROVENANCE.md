# DEV fixture provenance

These five texts are copied from the non-frozen DEV portion of
`evaluation/dev25/`. They are used as **document text only**; no gold labels
from this set are used, and no F1 or gate metric is computed.

| Doc ID | Source | Stratum | Manifest SHA-256 |
|---|---|---|---|
| `g7-dev-011` | `demo-data/documents/supply-chain-overview.txt` | relationship_heavy | `ba1db29e87762db14596505073764a18028ed69213bf0a2809fad33dd9ba00b0` |
| `g7-dev-013` | `demo-data/documents/risk-factors-2025.txt` | relationship_heavy | `e122bd2083c05aa07ce513009f2fe41881e482fad1dd3df06d731ffe35ba94d2` |
| `g7-dev-016` | `docs/acceptance/artifacts/vllm-production-eval/corpus/supply-chain-overview.epub` | cross_document_references | `e245b86391f4ca1debe592fa94226c33a762d4f985531d655a8b722e30a35702` |
| `g7-dev-018` | `docs/acceptance/artifacts/vllm-production-eval/corpus/acme-corp-profile.epub` | cross_document_references | `f184c179f41b3626d3c2500841a4beb85691babd09da83ff354f2bdfd76bcb78` |
| `g7-dev-023` | `demo-data/documents/structured-supply-chain.md` | tables_structured | `e135c0c1a794e44393de434963aa820211a7e3592f1c2533fe11f88b07951ace` |

## What is held out

`evaluation/gold/doc-1.json` through `doc-5.json` are the **ADR-022 frozen
5-document gold**. They are reserved for P2-3 and are not used for slice
development, tuning, E6, or latency evidence.

## Annotation-status conflict

The DEV-25 metadata is inconsistent:

- `evaluation/dev25/README.md` says only the five frozen documents are validated
  and the other 20 are `UNLABELED`.
- `evaluation/dev25/gold-release.md` says all 25 are annotated and released.
- `evaluation/dev25/manifest.tsv` marks all 25 `VALIDATED_GOLD`.

Because of that conflict, this slice uses only the DEV **texts** and does not
consume DEV labels. E6 gold-span alignment is deferred until the status is
reconciled.
