# YDB-POC-034 — cost model, write side (search side awaits 028)

**Status:** Partial (write-side inputs frozen; search-side + totals at 028/034 close)
**Sources:** 022 (`BENCH-YDB`), 032 burst/restart, schema §11.1-as-built.

## Measured write inputs (single-node PoC instance, this machine)

| Input | Value | Conditions |
|---|---|---|
| Atomic revision write p50 / p95 | 38.4 / 57.8 ms | 3-chunk revision + provenance + publication, single-threaded |
| Sustained revision throughput | 27.7 rps single-threaded; 77.6 rps at 20-way concurrency | zero partials in both modes |
| Metadata put+get p50 / p95 | 6.4 / 10.9 ms | single-threaded |
| Restart recovery | committed + pending survive; node back in ~30s | genuine (restart landed mid-test) |
| 77.6 rps framing | **capability demonstration, not comparison** — no Cassandra revision path exists to compare against; keep out of comparison tables | 034 rule |

## Storage sketch per document (3-chunk shape)

- 1 document row + 3 chunk rows (text + Base64 embedding) + 3 provenance rows
  + 1 publication row ≈ **8 rows, ~4 KB** at PoC text sizes (measure, don't
  assume, at corpus scale).
- Per 1M documents: ≈ 8M rows, ≈ 4 GB user data × replication factor of the
  target topology (PoC single-node: effectively ×1; production ×3 typical).
- Index overhead (FT + vector impl tables) unmeasured — 028/032 follow-up.

## Operational overhead (observed, single-node)

- One container, recovery automatic, no operator intervention in restart test.
- Headcount/complexity deltas vs Cassandra: **not yet assessed** — needs the
  failure-mode breadth of full 032 (node loss, not just restart) + 028 scale.

## Open for 034 close

Search-side cost per query (needs 028) · index storage overhead · replication
topology pricing basis · Cassandra metadata-write comparator (filed tracker item).
