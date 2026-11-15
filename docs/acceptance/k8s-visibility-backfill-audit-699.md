# #699 visibility backfill audit — 2026-11-15

**Status:** audit complete; **backfill not applied**, **fail-closed not enabled**.
**Tenant:** `demo`
**Tool:** `SourceVisibilityBackfill --audit` (read-only)
**Artifact:** `artifacts/k8s-visibility-backfill-699-2026-11-15/audit.txt`

## Decision

Hold. The 12 candidates with a completed lifecycle status are latest-completed and latest-indexed. The 11 legacy candidates have no lifecycle status (`UNKNOWN/UNKNOWN`) but are the latest indexed revision; they require an explicit decision because the backfill would manufacture a current pointer for a source with no completion evidence. No apply or switch is performed until that decision is recorded.

**Invariant:** the backfill repairs missing current-revision metadata; it must not manufacture an activation decision from incomplete or failed ingestion history.

## Findings

- 23 repairable sources:
  - 12 have `KNOWLEDGE_READY/GRAPH_COMMITTED` (or `PARTIAL`), `is_latest_completed=true`, `is_latest_indexed=true`.
  - 11 are legacy `UNKNOWN/UNKNOWN`, `is_latest_completed=legacy`, `is_latest_indexed=true`.
  - 0 are `FAILED`, `IN_PROGRESS`, or `PENDING`; 0 have `is_latest_indexed=false`.
- The superseded sample is 9 chunks across all 6 sources with superseded chunks (2 each where available; three sources have only one). Every sampled `is_older=true`.
- The 19,096 `superseded` chunks are older revisions by observed time; the sample confirms the classification.

## Audit table 1 — repairable candidates

```text
=== #699 audit table 1: repairable candidates ===
| source_id | selected_revision | observed_at | status | content_ref_id | latest_completed | is_latest_completed | latest_indexed | is_latest_indexed | safe |
| books-demo | d93a61b4-8ac9-5138-8f31-154130f325d0 | 2026-10-21T05:30:35.785Z | UNKNOWN/UNKNOWN | 29890128-88d8-5ac4-8ce6-c7ccebed0a6c | LEGACY/UNKNOWN | legacy | d93a61b4-8ac9-5138-8f31-154130f325d0 | true | true |
| c3-books | e94c3c46-2e54-557a-b408-4e1fbeb3ce34 | 2026-10-21T11:13:42.765Z | UNKNOWN/UNKNOWN | 2382ec04-3e27-5ada-82c2-a374e0dc324a | LEGACY/UNKNOWN | legacy | e94c3c46-2e54-557a-b408-4e1fbeb3ce34 | true | true |
| c3b-books | 38f43ea1-cdbd-5439-97ef-2d2cd0393469 | 2026-10-21T12:02:42.814Z | UNKNOWN/UNKNOWN | 910e9c74-f1b7-56aa-bbc0-93bc362d154b | LEGACY/UNKNOWN | legacy | 38f43ea1-cdbd-5439-97ef-2d2cd0393469 | true | true |
| combined-fault-1794425728 | 9701b42a-432f-5adb-ac91-ada459149fc2 | 2026-11-11T19:35:29.904Z | KNOWLEDGE_READY/GRAPH_COMMITTED | 875d8bd9-4d03-53b0-816a-e78f2921185f | 9701b42a-432f-5adb-ac91-ada459149fc2 | true | 9701b42a-432f-5adb-ac91-ada459149fc2 | true | true |
| e650 | 513d45be-005b-50de-bb5f-f6cfe4723cbb | 2026-11-06T16:58:25.480Z | UNKNOWN/UNKNOWN | 1b7558e5-2e82-568b-86a4-1612580e7089 | LEGACY/UNKNOWN | legacy | 513d45be-005b-50de-bb5f-f6cfe4723cbb | true | true |
| entity-graph-live-1794415977310 | 3bf05435-4668-5b41-a7f6-85caf80d233b | 2026-11-11T16:52:57.631Z | KNOWLEDGE_READY/GRAPH_COMMITTED | 875d8bd9-4d03-53b0-816a-e78f2921185f | 3bf05435-4668-5b41-a7f6-85caf80d233b | true | 3bf05435-4668-5b41-a7f6-85caf80d233b | true | true |
| entity-graph-live-1794417948863 | 2e363066-20ae-5117-86a6-7b6eff0d2181 | 2026-11-11T17:25:49.243Z | KNOWLEDGE_READY/GRAPH_COMMITTED | 875d8bd9-4d03-53b0-816a-e78f2921185f | 2e363066-20ae-5117-86a6-7b6eff0d2181 | true | 2e363066-20ae-5117-86a6-7b6eff0d2181 | true | true |
| entity-graph-live-1794419231507 | a0b5d4e1-f9e3-57f2-8602-f3de0e9f91fc | 2026-11-11T17:47:11.773Z | KNOWLEDGE_READY/GRAPH_COMMITTED | 875d8bd9-4d03-53b0-816a-e78f2921185f | a0b5d4e1-f9e3-57f2-8602-f3de0e9f91fc | true | a0b5d4e1-f9e3-57f2-8602-f3de0e9f91fc | true | true |
| entity-graph-live-1794419350315 | 12788bf2-4721-5a25-ba33-e40a47569eb8 | 2026-11-11T17:49:10.637Z | KNOWLEDGE_READY/GRAPH_COMMITTED | 875d8bd9-4d03-53b0-816a-e78f2921185f | 12788bf2-4721-5a25-ba33-e40a47569eb8 | true | 12788bf2-4721-5a25-ba33-e40a47569eb8 | true | true |
| entity-graph-live-1794421864922 | 3eb55681-6d9e-514d-86a6-d9a4a9ceb12b | 2026-11-11T18:31:05.215Z | KNOWLEDGE_READY/GRAPH_COMMITTED | 875d8bd9-4d03-53b0-816a-e78f2921185f | 3eb55681-6d9e-514d-86a6-d9a4a9ceb12b | true | 3eb55681-6d9e-514d-86a6-d9a4a9ceb12b | true | true |
| entity-graph-live-1794422064080 | b7af80ef-57fc-53b6-ac6e-871fe7b526cd | 2026-11-11T18:34:24.342Z | KNOWLEDGE_READY/GRAPH_COMMITTED | 875d8bd9-4d03-53b0-816a-e78f2921185f | b7af80ef-57fc-53b6-ac6e-871fe7b526cd | true | b7af80ef-57fc-53b6-ac6e-871fe7b526cd | true | true |
| entity-graph-live-1794422195438 | 52fbaadb-3973-52ab-8a4b-65575267e257 | 2026-11-11T18:36:35.734Z | KNOWLEDGE_READY/GRAPH_COMMITTED | 875d8bd9-4d03-53b0-816a-e78f2921185f | 52fbaadb-3973-52ab-8a4b-65575267e257 | true | 52fbaadb-3973-52ab-8a4b-65575267e257 | true | true |
| entity-graph-live-1794425838674 | f747ed92-fd5b-5406-b181-c3525ae6a8a1 | 2026-11-11T19:37:18.903Z | KNOWLEDGE_READY/GRAPH_COMMITTED | 875d8bd9-4d03-53b0-816a-e78f2921185f | f747ed92-fd5b-5406-b181-c3525ae6a8a1 | true | f747ed92-fd5b-5406-b181-c3525ae6a8a1 | true | true |
| entity-graph-live-1794509913003 | 5d8b5c42-ea27-53cb-900e-ce40f16df02f | 2026-11-12T18:58:33.549Z | KNOWLEDGE_READY/GRAPH_COMMITTED | 875d8bd9-4d03-53b0-816a-e78f2921185f | 5d8b5c42-ea27-53cb-900e-ce40f16df02f | true | 5d8b5c42-ea27-53cb-900e-ce40f16df02f | true | true |
| entity-graph-live-1794510041364 | c354e87c-db83-5fe2-886f-de4a15d95590 | 2026-11-12T19:00:41.722Z | KNOWLEDGE_READY/GRAPH_COMMITTED | 875d8bd9-4d03-53b0-816a-e78f2921185f | c354e87c-db83-5fe2-886f-de4a15d95590 | true | c354e87c-db83-5fe2-886f-de4a15d95590 | true | true |
| fault-injection-1794421490 | eb222407-519b-56cd-8d86-e60f6dbc5f58 | 2026-11-11T18:24:52.137Z | KNOWLEDGE_READY/PARTIAL | 875d8bd9-4d03-53b0-816a-e78f2921185f | eb222407-519b-56cd-8d86-e60f6dbc5f58 | true | eb222407-519b-56cd-8d86-e60f6dbc5f58 | true | true |
| gw-check | b75c87cc-20c0-5c74-a509-d1e8a3ff32a0 | 2026-10-21T05:53:13.267Z | UNKNOWN/UNKNOWN | d242216d-805f-5169-b25f-dc2877b419eb | LEGACY/UNKNOWN | legacy | b75c87cc-20c0-5c74-a509-d1e8a3ff32a0 | true | true |
| halcyon | 6da5d403-1b7e-5a1b-afda-3ad472bb4648 | 2026-11-06T17:03:48.146Z | UNKNOWN/UNKNOWN | f144d888-616c-536a-a97c-0f56a0e9da2e | LEGACY/UNKNOWN | legacy | 6da5d403-1b7e-5a1b-afda-3ad472bb4648 | true | true |
| p0-books | f3c1cc0f-cb60-5602-9cc3-4888ec55c311 | 2026-10-21T05:41:18.925Z | UNKNOWN/UNKNOWN | bdf91543-1dd9-5509-89ab-5f559cd057ca | LEGACY/UNKNOWN | legacy | f3c1cc0f-cb60-5602-9cc3-4888ec55c311 | true | true |
| p0-books-c1 | 11917f8d-f5df-586d-8264-bd9c61c361cb | 2026-10-21T10:37:28.200Z | UNKNOWN/UNKNOWN | bdf91543-1dd9-5509-89ab-5f559cd057ca | LEGACY/UNKNOWN | legacy | 11917f8d-f5df-586d-8264-bd9c61c361cb | true | true |
| p0-books-c1-dup | c5033de8-c3eb-5a8e-a6f9-4bbde0b6d076 | 2026-10-21T10:43:53.967Z | UNKNOWN/UNKNOWN | bdf91543-1dd9-5509-89ab-5f559cd057ca | LEGACY/UNKNOWN | legacy | c5033de8-c3eb-5a8e-a6f9-4bbde0b6d076 | true | true |
| peregrine | 8fe414fd-2809-5e76-84d5-f23765f3ea13 | 2026-11-08T11:31:01.475Z | UNKNOWN/UNKNOWN | 77e86b4b-1d45-5851-9f48-eb3425d6e764 | LEGACY/UNKNOWN | legacy | 8fe414fd-2809-5e76-84d5-f23765f3ea13 | true | true |
| ui-upload-browser | e8c44869-462a-5ec7-9856-260b2cdd2aa7 | 2026-11-08T14:00:01.609Z | UNKNOWN/UNKNOWN | 3cf6f3af-4487-5d9a-a5bd-55e5425695d8 | LEGACY/UNKNOWN | legacy | e8c44869-462a-5ec7-9856-260b2cdd2aa7 | true | true |
AUDIT: 11 legacy candidates have no completed-status row; review required
```

## Audit table 2 — superseded stratified sample

```text
=== #699 audit table 2: superseded stratified sample ===
| chunk_id | source_id | indexed_revision | current_revision | indexed_observed_at | current_observed_at | is_older |
| 9dc0282d-8998-55f4-9251-896955bf4278:22 | books-demo | 8f0178e0-fee6-5c45-9c6e-4ba8c8ad6b16 | d93a61b4-8ac9-5138-8f31-154130f325d0 | 2026-10-21T05:30:35.717Z | 2026-10-21T05:30:35.785Z | true |
| 9dc0282d-8998-55f4-9251-896955bf4278:5 | books-demo | 8f0178e0-fee6-5c45-9c6e-4ba8c8ad6b16 | d93a61b4-8ac9-5138-8f31-154130f325d0 | 2026-10-21T05:30:35.717Z | 2026-10-21T05:30:35.785Z | true |
| 90dc581d-e09e-503d-9907-e5522cf179d9:1715 | ui-upload-browser | 9b2b5b31-0097-502b-b21a-19515fb08937 | e8c44869-462a-5ec7-9856-260b2cdd2aa7 | 2026-10-21T14:43:22.024Z | 2026-11-08T14:00:01.609Z | true |
| ab0b3d11-1da1-5ec7-93b7-5d0d638f3741:196 | ui-upload-browser | d98039cc-49df-58c3-a418-679f4fd6b9b5 | e8c44869-462a-5ec7-9856-260b2cdd2aa7 | 2026-10-21T14:42:41.297Z | 2026-11-08T14:00:01.609Z | true |
| e8d93492-5773-587c-bc7b-d7f5fc531336:21 | c3b-books | 354aa079-5ffb-5146-89fd-ac5112590852 | 38f43ea1-cdbd-5439-97ef-2d2cd0393469 | 2026-10-21T11:44:06.985Z | 2026-10-21T12:02:42.814Z | true |
| e8d93492-5773-587c-bc7b-d7f5fc531336:37 | c3b-books | 354aa079-5ffb-5146-89fd-ac5112590852 | 38f43ea1-cdbd-5439-97ef-2d2cd0393469 | 2026-10-21T11:44:06.985Z | 2026-10-21T12:02:42.814Z | true |
| fab654b2-30c5-5e07-a43c-848a3406c2aa:0 | acceptance-695c-1794731388 | de0eaeae-c0ed-57ba-a9cf-60685fa73260 | b75f917a-66d0-55f5-99e9-3c2541e3b18d | 2026-11-15T08:29:48.965Z | 2026-11-15T08:32:07.622Z | true |
| 6d7dae1c-c428-52a9-a790-382e4e1f21c0:0 | acceptance-695e-1794732035 | b671fb2a-d9e1-5571-80a3-b39c18aa053e | 1a27bc31-481d-5dd2-987f-458ceae2e13a | 2026-11-15T08:40:36.199Z | 2026-11-15T08:42:14.850Z | true |
| 553d4667-ba2a-5b9b-9c22-c1a7437d43c0:0 | e650 | c58942c5-1d24-5db6-b6d2-40293e199efc | 513d45be-005b-50de-bb5f-f6cfe4723cbb | 2026-11-06T16:53:58.078Z | 2026-11-06T16:58:25.480Z | true |
AUDIT: sampled 9 chunk(s) from 6 source(s) of 6 with superseded chunks
```
