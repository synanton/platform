# #731 live retry-provider consistency

**Date:** 2026-11-17
**Tenant:** `eval-smoke`
**Source/resource:** `eval-retry-source` / `eval-retry.txt`
**Version:** `bb53be4c-abfd-5dbd-8cf9-23393aa92a4f`

## Procedure

1. Set `tenant_provider_config(eval-smoke).provider = llm`.
2. Ingest one smoke document and wait for the first extraction.
3. Flip `tenant_provider_config(eval-smoke).provider` to `regex`.
4. Force the extraction event back to `EXTRACTION_FAILED` with a due
   `next_attempt_at`, leaving `source_versions.extraction_provider = llm`.
5. Wait for the retry and compare provider attribution.

## Evidence

First extraction:

```text
Extraction completed tenant=eval-smoke ... engine=LlmExtractionAdapter
provider=llm provider_source=tenant-override
```

After the config flip and forced retry:

```text
Extraction completed tenant=eval-smoke ... engine=LlmExtractionAdapter
provider=llm provider_source=persisted
```

Cassandra:

```text
version_id                           | extraction_provider | extraction_status | last_error
bb53be4c-abfd-5dbd-8cf9-23393aa92a4f | llm                 | KNOWLEDGE_READY   | null
```

The retry used the persisted provider, not the current tenant config (`regex`).
This closes the live retry-provider consistency gate; no provider default was
changed and no #723 evaluation ingestion was started.
