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
3. Keep English files at their current paths. If the repo has no existing localization convention, mirror them under `docs/ru/` (for example, `docs/security/identity.md` → `docs/ru/security/identity.md`).
4. Check existing docs tooling before adding front matter, language selectors, or translation-status metadata.
5. Start with a small, architecture-central pilot document and validate the terminology and cross-link approach before translating the full corpus.

## Important limitation

The kit defines a workflow and baseline terminology; it does not assert that every repository document has been inspected or that the glossary is already approved by Synanton maintainers. Validate terms against canonical designs and existing project conventions before treating them as authoritative.
