# Request provenance / evidence isolation status

**Status:** implementation only; no inference, spend or execution authorization.

- `request_provenance.py` builds Arm A/B serialized requests and records exact
  `source_input_hash`, `ontology_block_hash` and `serialized_context_hash`.
- Hashes are computed from the final serialized payload string.
- Arm A does not call the ontology provider; Arm B requires a pinned snapshot.
- `validate_proposal(..., ontology_context=...)` rejects ontology-only text cited
  as evidence with `ONTOLOGY_CONTEXT_NOT_EVIDENCE`.
- Evidence offsets are always resolved against the original chunk text.
- Models still return `ModelProposal`; only `ValidatedExtraction` is produced.

Run:
```bash
cd evaluation/dev25
python3 -m unittest -v test_request_provenance.py
```
