# P0 first compiler run

Generated from the published `it-books-v1` release through the production Syntology API.

- Release: `47fe38f8-9f72-481b-826a-6c5b12a6027a`
- Release digest: `3a4a5edf876792ac92fa63494bd7181e4d8eb3826fb70e6fe4112d796be3831c`
- Compiler: `p0-1`, profile `it-books-v1-default`, schema version `1`
- Deployed image: `local-registry:5000/synanton/syntology@sha256:f6e4784a1f6f8cbb580d90b4511a224728163dc22f75871a9de34b0e8528dfb7`
- SPEC digest: `8834d45a276792babbdf59ff6da43bf11ba5a8bdd43ca9755cfb159559694455`
- Template digest: `333a900292eebabda75b4ed67ec909ecc5db450702e05ae23d108ed2cf2fb37b`
- Compilation ID: `cea21d1a4cebf9e45e2e6ebba9ae4c3899dfc336b2b098a959db6dd5a06f7e0b`
- Unsupported constructs: 0

The three JSON files are byte-identical across repeated runs. See
`docs/implementation/it-books/extraction-compiler.md` for the command and canonicalization rules.

See also:

- `docs/implementation/it-books/p0/scope-decision.md` — durable 9 support / 11 extraction decision.
- `docs/implementation/it-books/p0/a2-coverage-reconciliation.md` — required A2 scope alignment.
- `docs/implementation/it-books/extraction-compiler.md` — canonicalization and release-by-ID read path.
