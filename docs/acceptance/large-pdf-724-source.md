# #724 real large-PDF source

**Date:** 2026-11-17
**Source:** Google 2023 Environmental Report (public PDF)
**Staged as:** `docs/acceptance/artifacts/vllm-production-eval/corpus/google-2023-environmental-report.pdf` (ignored; not committed)
**Manifest entry:** `google-2023-environmental-report.pdf` in `vllm-production-eval-corpus.tsv`

## Verification

```text
Pages: 104
File size: 6,637,276 bytes
Extracted words: 53,119
SHA-256: 4af4c647a6b309c75967590bda80404b15355744d7d678a205facc6901e89b80
```

The PDF has extractable text, so it satisfies the real large-document source
requirement rather than the generated-PDF fallback. The #724 throughput and
stage-duration benchmark still needs to run; this document only establishes the
source and manifest identity.
