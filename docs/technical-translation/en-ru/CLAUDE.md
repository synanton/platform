# Synanton Platform Documentation: English ↔ Russian

## Mission

Maintain accurate, reviewable bilingual documentation for Synanton Platform, beginning with the existing documentation under `docs/`. English and Russian must describe the same platform contract. Translation is not a redesign, simplification, or opportunity to silently reconcile inconsistencies.

The English documentation in the existing repository is the default source of truth unless a document explicitly declares another authority. Russian documents are corresponding language editions, not independent specifications.

## First action: inspect the repository

Before editing:
1. Inspect `docs/`, its README/index files, document templates, links, naming conventions, and any existing localization structure.
2. Read the relevant source document completely, plus the documents it cites for architecture, security, identity, authorization, classification, and other authority boundaries.
3. Search for existing Russian text and project-specific terminology before creating or changing translations.
4. Follow an existing repository convention if one exists. If none exists, use a mirrored `docs/ru/` tree with the same relative paths as `docs/`, and keep English sources in their current locations.
5. Do not assume the public repository is fully represented by a partial search result. State when a source, link, or referenced design could not be inspected.

## Non-negotiable priorities

Apply in this order:
1. Preserve technical meaning, normative force, logical scope, and architecture.
2. Preserve the platform's explicit authority boundaries and invariants.
3. Keep English and Russian editions structurally aligned and traceable.
4. Follow the project's established terminology and naming conventions.
5. Produce natural, precise Russian for platform engineers, architects, security engineers, and operators.
6. Preserve formatting, code, references, and navigability.

## Bilingual document policy

- Treat each English/Russian pair as two editions of one document.
- Keep section order, headings, lists, tables, callouts, examples, diagrams, and normative statements aligned unless localization genuinely requires a structural change.
- Keep a stable mapping between source and translation. Default mapping: `docs/<path>.md` ↔ `docs/ru/<path>.md`; adapt only to an established repository convention.
- Do not translate English source files in place unless explicitly asked.
- Do not create a Russian file without confirming its intended source path and checking for an existing translation.
- Preserve document identifiers, design numbers, ADR IDs, filenames, stable anchors, API names, protocol names, configuration keys, and code identifiers.
- Translate prose in diagrams only when it is reader-facing and safe to localize; do not alter machine-readable labels or identifiers.
- Where a translated heading changes a generated Markdown anchor, verify and update internal links. Prefer explicit stable anchors when the project supports them.
- Links between language editions should be intentional: link to the same-language edition where available, and retain links to canonical English sources where authority or implementation context requires it.
- Do not imply that a Russian translation is newer or more authoritative than its English source.

## Platform architecture and normative meaning

Preserve distinctions the source makes between:
- identity, authentication, authorization, classification, policy, and trust;
- component responsibility, ownership, delegation, and enforcement;
- an architectural invariant, a design decision, an implementation detail, and a future intention;
- required, recommended, permitted, optional, prohibited, and unsupported behavior;
- control-plane and data-plane responsibilities, when the source distinguishes them;
- canonical authority and derived, replicated, or contextual data.

Never weaken or strengthen an invariant for fluency. For example, preserve the logical meaning of statements such as “Authentication never implies authorization” and “Classification is not authorization” if they occur in the source. Do not expand them into broader claims without evidence.

For normative terms, follow the source document's own convention. If the repository defines a mapping for RFC-style terms, use it consistently. Otherwise use:
- MUST / MUST NOT: «ДОЛЖЕН / НЕ ДОЛЖЕН» only if the repository treats these as normative keywords; otherwise preserve the English keyword and explain the convention once.
- SHOULD / SHOULD NOT: «СЛЕДУЕТ / НЕ СЛЕДУЕТ» only if defined by the repository's convention.
- MAY: «МОЖЕТ» only when it means permission, not capability.
- can / cannot: capability or possibility, not automatically permission.
Do not introduce all-caps Russian normative keywords if the source has no formal normative-keyword system.

## Russian style

- Use concise, idiomatic, professional technical Russian.
- Prefer concrete verbs and direct syntax; avoid word-for-word calques, bureaucratic filler, marketing language, and ornamental synonyms.
- Keep paragraphs focused without deleting constraints or reasoning.
- Preserve the source's audience, level of detail, and degree of certainty.
- Use English in parentheses at first mention only when it disambiguates a term or supports cross-language traceability. Do not repeat English glosses on every occurrence.
- Preserve established product, subsystem, design, protocol, API, and code names. Do not invent Russian names for identifiers.
- Prefer consistent terminology over stylistic variation.
- Use the Russian edition of Martin Kleppmann's *Designing Data-Intensive Applications* as a terminology and exposition reference where relevant. Do not imitate its prose or reproduce its text. The platform's own glossary and established usage take precedence.

## Formatting and source integrity

- Preserve Markdown structure, front matter, HTML/MDX components, tables, admonitions, footnotes, code fences, and link destinations unless a deliberate localized link change is required.
- Never translate code, commands, API paths, identifiers, filenames, environment variables, schema fields, configuration keys, log literals, or machine-readable values unless explicitly requested.
- Preserve placeholders and template syntax exactly, including `${VAR}`, `{{token}}`, `{name}`, `%s`, and interpolation expressions.
- Do not silently change dates, numbers, units, version constraints, examples, diagrams, or references.
- Do not invent implementation status, requirements, security guarantees, ownership, dependencies, or rationale.
- If the English source appears inconsistent with another design, do not silently resolve the conflict in Russian. Report the inconsistency with document references for an author decision.

## Terminology source order

Resolve terminology in this order:
1. Explicit definitions and glossary in the relevant platform document.
2. Existing Synanton glossary and consistent usage in related canonical designs.
3. Existing Russian platform translations, if their use is validated.
4. ` .claude/skills/platform-docs-bilingual-en-ru/glossary.md` (baseline glossary).
5. Established Russian software-engineering usage.
6. The Russian Kleppmann edition as a secondary reference for applicable distributed-systems terms.

When a term choice affects architecture or policy, record it in the glossary with context and rationale rather than changing it ad hoc.

## Workflow

For each document pair:
1. **Inventory:** identify source path, target path, document type, audience, status, owner/authority, references, and translation status.
2. **Context read:** read the whole source and its important dependencies before translating.
3. **Term extraction:** collect domain terms, defined terms, normative verbs, component names, and invariants.
4. **Translate:** translate meaning, preserving all claims and structure.
5. **Align:** compare heading hierarchy, tables, lists, examples, links, identifiers, and cross-references.
6. **Semantic diff:** check additions, omissions, negation, quantifiers, conditions, modality, actor/authority, and causal relationships.
7. **Cross-document review:** verify that shared terms and references agree with the relevant platform designs.
8. **Russian edit:** improve fluency without changing the contract.
9. **Status update:** update the translation inventory/status file if the repository uses one; otherwise propose one rather than creating a parallel tracking mechanism without need.
10. **Report:** summarize files changed, unresolved source ambiguities, terminology decisions, and any links that could not be verified.

## Suggested tracking metadata

Use the repository's existing metadata convention where possible. If none exists, a translation inventory may track:
- English source path
- Russian target path
- translation status (`not-started`, `draft`, `reviewed`, `stale`)
- source revision or commit
- last synchronized date
- reviewer / review status
- unresolved terminology or source questions

Do not add front matter to documents if the documentation renderer or repository convention does not support it. A separate inventory is safer when metadata support is unknown.

## Default task behavior

- “Translate”: create/update only the corresponding Russian edition; do not rewrite the English source.
- “Review translation”: compare source and target, report defects, then edit only if requested.
- “Synchronize”: identify source changes since the last translation and update the Russian edition without overwriting valid translation-specific navigation.
- “Make bilingual”: inspect the repo convention first, then create a traceable source/target pair.
- “Review architecture”: do not translate as a substitute for reviewing the technical correctness of the source.

## Final quality gate

Before finishing, verify:
- Every normative statement and architecture invariant is preserved.
- English and Russian editions cover the same claims, conditions, exceptions, examples, and warnings.
- Terms are consistent across all touched documents.
- Links and anchors resolve in the intended language edition.
- Code, identifiers, paths, placeholders, and machine-readable syntax are unchanged.
- No source conflict was silently reconciled.
- The change is scoped to the requested documents and includes a concise change report.
