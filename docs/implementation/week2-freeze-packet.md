# Week-2 freeze packet (F-8 — for the scheduling owner to execute)

Freezing = removing `-draft` from the three schema versions and recording the
freeze commit. After freezing, any schema change requires a version bump +
migration note, never a silent edit.

## 1. Freeze candidates (verify current before signing)

| Artifact | Current version | Freeze to |
|---|---|---|
| `schemas/benchmark/benchmark-manifest.schema.json` | `0.1.0-draft` (check) | `0.1.0` |
| `schemas/benchmark/result-manifest.schema.json` | `0.1.0-draft` (check) | `0.1.0` |
| `docs/benchmark/metric-taxonomy.md` | taxonomy text (no version — add `1.0` header at freeze) | `1.0` |
| Runner `runner_version` | `0.2.0` (cli.py) | record, don't freeze (code, not schema) |

## 2. Pre-freeze checks (all must pass day-of)

- [ ] All 10 DOC-evidence docs pass the D4.1 citation check (procedure in
  tracker DOC-D4.1 entry; re-run, don't trust this packet).
- [ ] D0 references (`manifest-reference.md`, `result-reference.md`,
  `metric-taxonomy.md`) describe the frozen versions exactly — no
  draft-only fields.
- [ ] Runbook validation log green against the frozen CLI (rerun fresh-eyes
  pass if the CLI changed since DOC-D1.1).
- [ ] Fixture manifests (`schemas/benchmark/fixtures/*.json`) validate
  against the frozen schemas.
- [ ] B5 pre-check formal: every B5 leg artifact cites frozen schema versions.

## 3. Freeze commit contents

1. Version bumps (drop `-draft`) in both schema files.
2. `1.0` header in `metric-taxonomy.md`.
3. Tracker Week-2 entry: date, owner, freeze commit hash, checklist results.
4. This packet archived as executed (fill in below).

## 4. Execution record (fill at scheduling)

- Date:
- Owner:
- Freeze commit:
- Exceptions (if any §2 item waived, with reason + follow-up):
