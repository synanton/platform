# extraction-probe

A small CLI to test `content_extractor`'s `extraction-gateway` directly: upload a
document (PDF, text, markdown, ...), call the real `synanton.extraction.v1`
`ExtractSync` RPC, and save the textual response to a target folder.

Unlike the ad-hoc probes used to diagnose specific bugs during development, this
is a proper, reusable tool - point it at any reachable `extraction-gateway` and
object store, and it handles the upload → extract → save flow end to end.

## Setup

```bash
cd tools/extraction-probe
python3 -m venv .venv
source .venv/bin/activate
pip install -e '.[dev]'
```

## Usage

Point it at a running `extraction-gateway` and object store (defaults assume
both are reachable at `localhost` - override via env vars or `--config`, see
`config.yml`):

```bash
extraction-probe extract \
  --input /path/to/document.pdf \
  --output-dir /path/to/target-folder
```

This uploads the file to the configured MinIO/S3 bucket, calls `ExtractSync`,
and writes two files into `--output-dir`, named after the input:

- `<name>.txt` - the flattened text (empty on failure)
- `<name>.json` - full outcome metadata: `status`, `feature_states`,
  `element_count`, and `error_code`/`error_diagnostic` when the call failed

Exit code is `0` for `STATUS_COMPLETED`/`STATUS_PARTIAL`, `1` otherwise - so it's
usable as a quick pass/fail check, not just a manual inspection tool.

### Pointing at a different gateway/object store

```bash
export EXTRACTION_GATEWAY_HOST=my-host
export EXTRACTION_GATEWAY_GRPC_PORT=9091
export EXTRACTION_OBJECTSTORE_ENDPOINT=http://my-minio:9000
export EXTRACTION_OBJECTSTORE_ACCESS_KEY=...
export EXTRACTION_OBJECTSTORE_SECRET_KEY=...
extraction-probe extract --input document.pdf --output-dir out/
```

See `config.yml` for every overridable setting, and its comments for what each
one maps to on the `content_extractor` / `extraction-gateway` side.

### Media type

Auto-detected from the file extension (`.pdf` → `application/pdf`, `.txt` →
`text/plain`, `.md` → `text/markdown`, etc.) - override with `--media-type` for
anything else the gateway's adapters support.

## How stub generation works

`extraction_probe/stubs.py` generates the gRPC Python stubs from the real
`.proto` files at `proto.source_dir` in `config.yml` (default:
`../../java/extraction-contract/src/main/proto`, i.e. this platform checkout's
own mirrored copy of `synanton.extraction.v1`) on first use, caching them under
`.generated-stubs/` (gitignored). They're regenerated automatically if the
source `.proto` files change - stubs are never committed, so they can't
silently drift from the contract.

## Example

```bash
extraction-probe extract --input demo-data/documents/quarterly-report.pdf --output-dir /tmp/extraction-out
# ...
# status=STATUS_COMPLETED
# flattened_text_len=1842 element_count=12
# feature_states={'text': 'FEATURE_APPLIED', 'layout': 'FEATURE_APPLIED', ...}
#
# Wrote /tmp/extraction-out/quarterly-report.txt
# Wrote /tmp/extraction-out/quarterly-report.json
```
