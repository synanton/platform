# DeepSeek Phase 2 reconstruction artifacts

- `deepseek-p2-executions.hex` — raw persisted gateway `executions.result` rows for
  `p2-*` successful calls (request_id + hex-encoded result). SHA-256:
  `0adc409bc056ddd3608a43001e8b09c98ba76be642251de94cb7968b2a196c6f`.
- `deepseek-partial.log`, `deepseek-batch2.log` — partial run logs.
- `deepseek-batch2-summary.json` — batch 2 metrics (relation F1 invalid).

No new inference was used to create these artifacts. The scorer fix is in
`evaluation/dev25/relation_scoring.py` with tests in
`evaluation/dev25/test_relation_scoring.py`.
