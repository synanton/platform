# Pre-run observability status

**Status:** implementation only; no inference, spend or execution authorization.

- Gate 1: dry telemetry harness captures device identity, total memory, allocated/
  reserved peaks, interval samples and capture boundaries. Missing telemetry is
  ineligible, never zero.
- Gate 2: immutable identity chain `run_id → request_id → document_id → chunk_id
  → arm_id → attempt_id → output_id → telemetry_record_id` is minted before any
  attempt; retries increment attempt/output IDs.
- Gate 3: manifest schema checks model/tokenizer/runtime, prompt, chunk plan,
  ontology snapshot, retrieval algorithm, serialized context, gold/metric
  versions, decoding settings, arms and dataset.
- Gate 4: missing telemetry, manifest fields and identity components produce
  observable failures.
- Gate 5 remains design-level; no inference or arm execution is included here.
- Gate 6 authorization is still absent.

Run:
```bash
cd evaluation/dev25
python3 -m unittest -v test_pre_run_observability.py
```
