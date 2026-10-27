# Demo entity extraction spec (MVP)

## Purpose
Build a real, shallow graph for the demo. Regex and glossary extraction are real; do not mock entity chips or graph edges.

## Inputs
- Chunk text from the current 317-chunk demo corpus (digest `a993e6a1…`).
- `docs-ru/GLOSSARY.md` and `docs/technical-translation/en-ru/.claude/skills/platform-docs-bilingual-en-ru/glossary.md`.
- Query text at search time.

## Entity types and patterns

| Type | Pattern examples | Rule |
|---|---|---|
| identifier | `ERR_[A-Z0-9_-]+`, `SEC-[A-Z0-9-]+`, `IATF\s+\d+`, `ISO\s+\d+(:\d+)?`, `VLAN\d+`, IPv4, port numbers | Preserve exact case for display; normalize to upper-case for the key. |
| date | `\b(19\|20)\d{2}\b`, `Month DD, YYYY`, `DD Month YYYY`, `FY\d{4}` | Normalize to ISO-8601 when unambiguous. |
| version | `v\d+(\.\d+){1,3}`, `slice-\d{4,}`, `TEI\s+\d+(\.\d+)+`, explicit `version` context | Do **not** match raw section numbers like `6.6`; keep the original token; version key is the normalized string. |
| quoted phrase | double quotes, backticks, curly quotes, guillemets | Strip delimiters; require >= 2 tokens. |
| glossary term | exact case-insensitive match against the glossary headings/aliases | Longest match wins; store the canonical glossary term as alias. |
| FileNet class | `[A-Z][a-zA-Z]+(Engine|Manager|Store|Policy|Class|Object|Folder|Document|Queue|Definition|Subscription|Template)` | Keep class names; do not merge with prose phrases. |
| FileNet product | `FileNet\s+P8`, `Content\s+Manager`, `Business\s+Process\s+Manager`, `ECM\s+Widgets`, `REST\s+API` | Product/architecture terms; apply df cutoff. |
| legal citation | `[A-Z][a-z]+\s+v\.\s+[A-Z][a-z]+` | Case-name entity; cross-chapter bridge. |
| legal section | `(Section|§)\s*\d+(\.\d+)*`, `Article\s+[IVX]+`, `Restatement\s+\((Second|Third)\)\s+of\s+Contracts` | Section/citation entity; exact-match material. |
| legal doctrine | `consideration`, `promissory estoppel`, `expectation (interest|damages)`, `reliance (interest|damages)`, `good faith`, `third-party beneficiar(y|ies)` | Multi-word terms; keep `contract`/`court`/`damages` as stopwords. |

## Identity
- Entity ID: `ent:<type>:<sha1(normalized_surface)[:16]>`.
- Chunk node ID: `chunk:<content_ref_id>#<ordinal>`.
- Alias map: canonical -> surface forms; never merge two entity types.

## Relations
- `mentions`: chunk -> entity, weight 1.
- `alias_of`: surface -> canonical, weight 1.
- `co_occurs`: entity <-> entity when both occur in the same chunk, weight `1 / log(1 + df)` where `df` is the number of chunks containing the entity.
- One-hop graph leg: query entities -> seed chunks -> entity neighbors -> adjacent chunks; score = sum of rare-entity weights; cut at top-k.

## Query classification (adaptive RRF)
- `exact`: query contains identifier, date, version, or quoted phrase.
- `conceptual`: no exact feature and query contains a definition/explanation cue (`how does`, `why`, `define`, `explain`, `which ... best`).
- `mixed`: both or neither; default to mixed.
- Start weights: BM25 1.0, dense 1.0, graph 0.5; exact shifts +0.4 BM25/+0.3 graph/-0.4 dense; conceptual shifts +0.5 dense/+0.1 graph/-0.3 BM25. Clamp at 0.
- Log per-leg rank, contribution, and final fused score. The UI renders these; no attribution, no demo.

## DF cutoff
Drop entities with df > 50 from the graph. `Content Engine`, `Process Engine`,
`contract`, and `court` are expected stopwords at this corpus size.

## Acceptance
- Extraction runs over the demo corpus offline and is deterministic.
- Entity chips and graph edges are computed from real chunk text, not fixtures.
- The 20-query hygiene set includes exact, conceptual, and mixed queries.
