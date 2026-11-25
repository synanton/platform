# IT Books samples

Three rights-cleared, Creative Commons licensed IT books for local extraction and structural
contract runs.

These files are intentionally **outside** `demo-data/documents/`; they are not part of the
16-document demo/benchmark corpus unless explicitly ingested.

| Book | Edition | Pages | License |
|---|---|---:|---|
| Pro Git — Scott Chacon, Ben Straub | 2nd, 2.1.436 | 501 | CC BY-NC-SA 3.0 |
| Think Python — Allen B. Downey | 2nd | 244 | CC BY-NC 3.0 |
| Computer Networks: A Systems Approach — Larry Peterson, Bruce Davie | 6th, 6.1 | 489 | CC BY 4.0 |

## Provenance

See `manifest.json` for author, source URL, download URL, license basis, page count, byte size and
SHA-256. The exact source files are pinned by checksum in `SHA256SUMS`.

Verify from a clean checkout:

```bash
cd samples/it-books
sha256sum -c SHA256SUMS
```

## Use

Point an explicit ingestion/sample run at `samples/it-books/books/`, or use a single book as a test
input. Do not treat this directory as A2 evidence: A2 (#898) is the independent annotated fixture
and owns expected entity/relation judgments.

## License handling

This is not a relicensing. Each book remains under its original license:

- Pro Git: CC BY-NC-SA 3.0 — attribution, non-commercial, share-alike.
- Think Python: CC BY-NC 3.0 — attribution, non-commercial.
- Computer Networks: CC BY 4.0 — attribution.

Full license texts are in `licenses/`; attribution and source details are in `NOTICE.md`.
