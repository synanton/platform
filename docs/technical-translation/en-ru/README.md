# Synanton bilingual technical documentation kit

This kit configures Claude to maintain English/Russian editions of Synanton Platform documentation, starting from [`synanton/platform/docs`](https://github.com/synanton/platform/tree/main/docs).

## Contents

- `CLAUDE.md` — repository-level rules for bilingual documentation.
- `.claude/skills/platform-docs-bilingual-en-ru/SKILL.md` — reusable workflow for translation, review, and synchronization.
- `.claude/skills/platform-docs-bilingual-en-ru/glossary.md` — starter EN–RU glossary with architecture-sensitive distinctions.
- `translation-prompt.md` — standalone prompt for use in a chat or coding agent.

## Suggested setup

1. Review `CLAUDE.md` against the repository's current contributor instructions before merging it.
2. Copy the skill directory into the platform repository's `.claude/skills/` directory.
3. Keep English files at their current paths. For Synanton, mirror translated design documents under `docs-ru/` with corresponding relative paths (for example, `docs/architecture/synanton-design-1.32.md` → `docs-ru/architecture/synanton-design-1.32.md`).
4. Check existing docs tooling before adding front matter, language selectors, or translation-status metadata.
5. Start with a small, architecture-central pilot document and validate the terminology and cross-link approach before translating the full corpus.

## Deterministic tooling

`tools/docs-ru/translation_tool.py prepare` builds a stable translation packet from the English source and `docs-ru/GLOSSARY.md`. `validate` checks a Russian mirror for preserved glossary identifiers, section references, code fences, field names, and a target path under `docs-ru/`. Translation prose is authored separately; the packet and validation report are deterministic for identical inputs.

Use `--sections START-END` for a bounded pilot such as Design 1.32 §§55–62; extraction includes those complete numbered sections and excludes adjacent sections.

```bash
python3 tools/docs-ru/translation_tool.py prepare \
  --source docs/api/error-model.md \
  --target docs-ru/api/error-model.md \
  --glossary docs-ru/GLOSSARY.md

python3 tools/docs-ru/translation_tool.py validate \
  --source docs/api/error-model.md \
  --target docs-ru/api/error-model.md \
  --glossary docs-ru/GLOSSARY.md
```

## Important limitation

The kit defines a workflow and baseline terminology; it does not assert that every repository document has been inspected or that the glossary is already approved by Synanton maintainers. Validate terms against canonical designs and existing project conventions before treating them as authoritative.
