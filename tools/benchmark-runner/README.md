# Benchmark Runner (BR-A1.1)

Manifest-driven benchmark execution (Track A). Early phase — only
manifest parsing and validation exist so far.

```bash
pip install -e tools/benchmark-runner
benchmark-runner validate schemas/benchmark/fixtures/six-composition.json
benchmark-runner verify <manifest.json> --corpus <corpus-path>
benchmark-runner run <manifest.json> --corpus <corpus-path> --queries queries.json --out runs/ [--dry-run]
```

See `docs/implementation/benchmark-runner-vector/track-a-benchmark-runner.md`.
