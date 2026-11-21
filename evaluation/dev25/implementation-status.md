# Offline extraction-improvement implementation status

**Status:** implemented offline; no provider calls, spend, or acceptance claim.

- `extraction_pipeline.py` implements chunk planning with coverage and output
  budgets, Stage-1 entity filtering, Stage-2 relation filtering with direction
  normalization and endpoint checks, and provenance-preserving chunk merge.
- `offline_validation.py` provides pure schema/evidence/endpoint validators.
- Output budgets are parameterized for **32K** (default) and **64K** completion
  ceilings; a 12K-character input can produce 3–4× as much JSON text.
- `preflight_output_budget` requires an explicit runtime provider-limit source,
  then checks every chunk before inference; missing limits fail closed.
- The 3× expansion factor is configurable per plan and recorded in each chunk
  (`output_char_ratio`) for observability.
- Oversized chunks return diagnostics with chunk ID, required tokens and provider
  cap.
- Tests cover chunk coverage/budget, 32K/64K caps, provider-capability rejection,
  bad spans/types, wrong predicates, missing endpoints, direction reversal,
  chunk provenance and all 25 DEV gold v1.0 docs.

Run:
```bash
cd evaluation/dev25
python3 -m unittest -v test_offline_validation.py test_extraction_pipeline.py
```

No experiment is authorized by this implementation.
