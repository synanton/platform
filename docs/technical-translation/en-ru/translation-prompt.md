# Prompt: maintain bilingual Synanton Platform documentation (English ↔ Russian)

You are editing bilingual technical documentation for Synanton Platform. The starting corpus is the repository's existing `docs/` tree:
https://github.com/synanton/platform/tree/main/docs

Goal: create or maintain a Russian edition that is semantically equivalent to the English source, architecturally faithful, consistent with Synanton terminology, and easy to review alongside the source.

## Before editing
- Inspect the repository's documentation layout, current localization conventions, and any existing Russian files.
- Identify the exact English source and target path. If no convention exists, mirror `docs/<path>.md` as `docs/ru/<path>.md`; do not move or overwrite English sources.
- Read the full source and linked design documents needed to understand terms, authority boundaries, and invariants.
- Search for existing glossary decisions and translated counterparts.
- If you cannot access a source or linked design, disclose that limitation instead of inferring its content.

## Translate and align
- Preserve all claims, requirements, recommendations, prohibitions, exceptions, uncertainty, and causal relationships.
- Preserve distinctions among identity, authentication, authorization, classification, policy, trust, ownership, delegation, and enforcement.
- Preserve normative force: MUST, MUST NOT, SHOULD, SHOULD NOT, MAY, can, and cannot are not interchangeable. Follow any normative-keyword convention already used by the project; do not invent one.
- Keep headings, section order, tables, examples, diagrams, callouts, and cross-references aligned.
- Preserve identifiers, design numbers, component names, APIs, protocols, paths, filenames, commands, code, configuration keys, placeholders, and machine-readable syntax.
- Translate prose into idiomatic, precise Russian. Avoid literal calques, bureaucratic filler, marketing tone, and gratuitous synonym variation.
- Use the project's own definitions and glossary first. Use the Russian edition of Martin Kleppmann's *Designing Data-Intensive Applications* as a secondary terminology reference for relevant distributed-systems concepts, not as a source of platform requirements or text to imitate.
- Do not silently reconcile conflicting source documents, add unsupported claims, or make the Russian edition more prescriptive than the English source.

## Review before completion
Compare source and target for omissions, additions, changed modality, negation, conditions, quantifiers, authority/ownership, examples, links, anchors, and identifiers. Verify cross-links point to the intended language edition. Flag source ambiguity or architectural inconsistency separately.

## Deliverable
Make the requested changes in the repository when tools permit. Report:
1. English source and Russian target paths;
2. key terminology decisions;
3. semantic or source conflicts that need a maintainer decision;
4. links/anchors that could not be verified;
5. translation status and any follow-up required.

If asked only to translate one passage, return only the Russian translation unless an ambiguity materially affects correctness.
