# P3b/P4 preflight report — IT Books

**Evaluation ID:** `it-books-p3b-p4-preflight-v1`
**Status:** PREFLIGHT ONLY — **evaluation not started**
**Parent:** #913 · **A2:** `it-books-a2-v1` · **P3a:** `p3a-evidence-binding-v1`

## Result

The accepted inputs are present and pinned. No evaluation was run because the extractor path is not
configured or explicitly authorized.

| Check | Result |
|---|---|
| A2 fixture present and accepted | PASS |
| A2 source + excerpt hashes | PASS |
| A2 mentions / relations | 22 / 17 |
| A2 predicates | 8, each with accepted + rejected example |
| A2 accepted / rejected / unjudged | 8 / 8 / 1 |
| P0 artifacts present | PASS |
| P0 release identity matches A2 | PASS |
| P1 contract present | PASS |
| P3a contract present | PASS |
| Extraction authorized | NO |
| Evaluation started | NO |

## Pinned identities

**A2**
- version: `it-books-a2-v1`
- merge commit: `c790a2390404593f2807eaa53fa1ace04348bc17`
- manifest SHA-256: `cf1d7d5c6791ef5e9c04a21d6b320453fca4b0d1aa1b49a90f0c24b111669f89`
- annotations SHA-256: `d71ed367c7a46c453766c72b13a295c0cc7fd93ec85908a761a51f5a251b8a43`

**P0**
- merge: `5a781ef8e33ad294421fe3c2155b0afc01a9730d`
- release: `47fe38f8-9f72-481b-826a-6c5b12a6027a`
- release digest: `3a4a5edf876792ac92fa63494bd7181e4d8eb3826fb70e6fe4112d796be3831c`
- compiler: `p0-1`, profile `it-books-v1-default`, schema `1`
- SPEC: `8834d45a276792babbdf59ff6da43bf11ba5a8bdd43ca9755cfb159559694455`
- template: `333a900292eebabda75b4ed67ec909ecc5db450702e05ae23d108ed2cf2fb37b`
- compilation: `cea21d1a4cebf9e45e2e6ebba9ae4c3899dfc336b2b098a959db6dd5a06f7e0b`

**P1**
- structural slice: `1b1a90e7`
- extraction integration: `15c88641`
- formal conformance: `5fa1df9d`
- pinned evidence: `99df0da7`
- A2 adapter: Synanton Markdown Adapter; normalization `NFC_UTF16_V1`; chunking `markdown-heading-v1`

**P3a**
- merge: `66afc878d467f95cc997eeb0e9201aa263f9cc1c`
- contract: `p3a-evidence-binding-v1`

## Sources and licenses

| Source | Edition | Original SHA-256 | License / restriction |
|---|---|---|---|
| Think Python | 2e, v2.4.1 | `6033000bac3f9268…` | CC BY-NC 3.0; attribution + non-commercial |
| Computer Networks | 6e, v6.1 | `e4ddb12a63f9eceb…` | CC BY 4.0; attribution |

Excerpt hashes and license URLs are in `preflight.json`. Original PDFs and excerpts are pinned; no
full book text was added.

## Required evaluation constraints

- A2 is the sole judgment authority; judged-negative and unjudged remain distinct.
- Exact, approximate, and document-scope grounding are reported separately.
- Geometry claims: Markdown A2 evidence is `NOT_APPLICABLE`; **PDF AVAILABLE-bbox claims must use
  P1's PDF fixture**.
- `sourcePage` references are manually reviewed, not validator-enforced.
- `mediaType`, `pageCount`, and structural-artifact digest are not yet in A2 manifest; this is a
  recorded non-blocking limitation, not a reason to expand A2.
- An extractor must be explicitly authorized/configured before evaluation; no backend is added by
  this preflight.
- Any GPU path must use `synanton.gpu.v1`; no direct vLLM/TEI calls and no automatic regex fallback.
- No production/customer index, Supply Chain generation, graph promotion, P2, or Docling work.

## Reproduction

```bash
python3 evaluation/it-books/p3b-p4/build_preflight.py
python3 -m unittest discover -s evaluation/it-books/a2-v1 -p 'test_*.py'
./gradlew :java:synflux:test \
  --tests 'org.synanton.synflux.document.evidence.A2FixtureValidationTest' --rerun-tasks
cd evaluation/it-books/a2-v1 && sha256sum -c SHA256SUMS
```

## Review decision requested

Review this manifest and authorize an explicit extractor path. Evaluation must not start until
that authorization and this preflight are accepted.
