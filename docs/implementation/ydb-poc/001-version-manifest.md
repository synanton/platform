# YDB-POC-001 — Pinned YDB Server and Java SDK Versions

**Status:** Closed (Phase 0A)
**Date:** 2026-09-26
**Sources:** https://github.com/ydb-platform/ydb/releases, https://github.com/ydb-platform/ydb-java-sdk/releases (checked 2026-09-26)

## Manifest

| Component | Pinned version | Channel | Released |
|---|---|---|---|
| YDB server | `26.3.1.16` | **Pre-release** | 2026-09-21 |
| YDB Java SDK (`ydb-java-sdk`) | `v2.4.11` | Latest stable | 2026-08-31 |

## Notes

- The proposal candidate is YDB 26.3.x, so the server pin follows the 26.3 line. As of this writing the newest 26.3 build (`26.3.1.16`) is marked **Pre-release**; the newest stable is `26.2.1.14` (2026-09-17).
- **Risk carried to YDB-POC-003/036:** evaluating against a pre-release server is a production risk. If 26.3 goes stable during the PoC, re-pin to the stable patch and re-run the benchmark subset (per §13 re-validation rule). If it stays pre-release at Phase 6, the decision package must record that explicitly.
- SDK `v2.4.11` is a routine bugfix release (session autorelease StackOverflow fix); no API break vs `v2.4.x` noted in its changelog.
- Re-validation rule: if either version changes mid-PoC, YDB-POC-001 reopens and the affected benchmark tickets re-run.
