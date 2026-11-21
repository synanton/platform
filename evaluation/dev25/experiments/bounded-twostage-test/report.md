# Bounded two-stage 3-chunk test — stopped

**Status:** STOPPED on first subchunk; schema/offset failure.
**Calls:** 1 (stage 1 only). Stage 2 and the other two chunks were not run.
**Spend:** $0.00702786 (gateway ledger).

## Result

- `failed-01`, `g7-dev-006`, subchunk 0.
- Stage 1 returned valid JSON (`stop`) with 35 entities.
- **All 35 entity offsets failed exact surface validation.**
- Example: model claimed `BERT` at `40:44`; the actual chunk offset is `47:51`
  (`chunk.find("BERT") == 47`). Other offsets are similarly displaced.
- Stage 2 was not run; controls and remaining chunks were not reached.

## Interpretation

The offset failure is not a JSON failure. The model emitted syntactically valid
entities but unreliable character offsets. The deterministic gate correctly
rejected them.

The next engineering fix should be surface anchoring: locate the emitted surface
form in the source text, use the located span, and reject/flag ambiguous or
missing surfaces. This must be tested offline before another model run.

## Decision

- No continuation, no controls, no expansion.
- No acceptance or ranking claim.
- 16K/two-stage hypothesis remains untested beyond stage 1.
- No further inference is authorized.
