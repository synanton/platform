# Ontology-only retrieval status

**Status:** offline implementation; no inference or spend.

- `ontology_context.py` deterministically retrieves tenant/version-pinned ontology
  classes, predicates and constraints by lexical overlap.
- Context is bounded by `max_context_chars` and `top_k`.
- Each snapshot records tenant, ontology version, query hash, object IDs, context
  hash and top-k.
- No graph retrieval is included in this increment.
- Tests cover relevance, tenant isolation, version pinning, determinism, budgets
  and top-k.

Run:
```bash
cd evaluation/dev25
python3 -m unittest -v test_ontology_retrieval.py
```
