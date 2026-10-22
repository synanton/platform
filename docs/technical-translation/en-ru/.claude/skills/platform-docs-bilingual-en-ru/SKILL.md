---
name: platform-docs-bilingual-en-ru
description: Maintain bilingual English/Russian Synanton Platform documentation. Use for translating, reviewing, synchronizing, or creating Russian counterparts for architecture designs, specifications, ADRs, security and identity documents, API references, READMEs, and runbooks.
---

# Synanton Platform bilingual documentation

## Trigger

Use this skill when asked to translate English platform docs into Russian, review an existing Russian edition, synchronize language editions, or establish platform-specific terminology.

## Required context pass

1. Inspect the repository's existing documentation layout and localization conventions.
2. Identify the English source and intended Russian target. Default to `docs/<path>.md` → `docs/ru/<path>.md` only when the repository has no established convention.
3. Read the complete source and the linked platform documents necessary to understand its terms, authority boundaries, and invariants.
4. Search for existing translations and glossary decisions before introducing new terminology.
5. If GitHub or a referenced file cannot be accessed, say so and avoid claiming to have checked it.

## Rules

- English is the default canonical source unless the document says otherwise.
- Maintain two editions of one specification, not two independent specifications.
- Preserve heading order, examples, diagrams, tables, callouts, links, anchors, normative statements, and cross-references.
- Preserve design IDs, component names, API symbols, filenames, identifiers, configuration keys, protocol names, and code.
- Translate prose, not machine-readable syntax.
- Preserve modal force and logical scope. `must`, `should`, `may`, `can`, `must not`, and `should not` have distinct meanings.
- Preserve distinctions between identity, authentication, authorization, classification, policy, and trust; and between canonical authority and derived data.
- Do not resolve inconsistencies between source documents on your own. Report the conflict and cite both locations.
- Use the project glossary and canonical design usage before general terminology references.
- Use the Russian Kleppmann edition only as a secondary reference for relevant distributed-systems terminology and exposition; never imitate or reproduce its prose.
- Do not invent status, guarantees, requirements, explanations, or examples.
- Avoid calques, bureaucratic phrasing, gratuitous anglicisms, and synonym variation that obscures a repeated concept.

## Procedure

### 1. Inventory
Record source path, target path, document purpose, audience, important references, and known translation status.

### 2. Translate
Translate by technical meaning and function, not English word order. Keep structure and scope aligned.

### 3. Terminology pass
Check `glossary.md` and nearby canonical designs. Add a glossary entry only when the term is reusable or its choice is consequential. Include context and disambiguation.

### 4. Semantic comparison
Compare source and target for:
- missing or added claims;
- negation, quantifiers, conditions, exceptions, and causal links;
- changes in obligation, recommendation, permission, or capability;
- altered authority, ownership, trust boundary, or enforcement responsibility;
- changed identifiers, values, examples, versions, and references.

### 5. Cross-link review
Check that links point to the correct language edition where available. Keep canonical English references where necessary to clarify authority. Verify localized anchors.

### 6. Russian edit
Improve idiom and readability without simplifying away architecture or changing certainty.

### 7. Report
State changed paths, key terminology decisions, unresolved ambiguities, source conflicts, and any inaccessible references.

## Output behavior

- For a direct translation task, return or write the translated document without a preamble unless the user asks for explanation.
- For review tasks, prioritize concrete defects and cite source/target headings or line ranges.
- For synchronization, describe which source changes were reflected and what remains stale.
- For ambiguous source meaning, add a separate “Questions for the source author” section rather than guessing.
