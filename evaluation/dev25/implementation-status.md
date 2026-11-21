# Offline extraction-improvement implementation status

**Status:** implemented offline; no provider calls, spend, or acceptance claim.

- `extraction_pipeline.py` implements chunk planning with coverage and output
  budgets, Stage-1 entity filtering, Stage-2 relation filtering with direction
  normalization and endpoint checks, and provenance-preserving chunk merge.
- `offline_validation.py` provides pure schema/evidence/endpoint validators.
- Tests cover chunk coverage/budget, bad spans/types, wrong predicates, missing
  endpoints, direction reversal, chunk provenance and all 25 DEV gold v1.0 docs.

Run:
```bash
cd evaluation/dev25
python3 -m unittest -v test_offline_validation.py test_extraction_pipeline.py
```

No experiment is authorized by this implementation.
