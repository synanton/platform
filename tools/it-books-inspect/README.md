# IT Books inspection tools

Read-only diagnostics for A4/A4.1. These tools do not rerun extraction, reconstruct historical
prompts, or write graph state.

```bash
python3 tools/it-books-inspect/inspect_a4.py \
  --a4-dir evaluation/it-books/p3b-p4/a4-inspection-v1 \
  --a2-dir evaluation/it-books/a2-v1 \
  --out evaluation/it-books/p3b-p4/a4-inspection-v1/report.md
```

The A4 regex path has no prompt or raw model response; those fields are explicitly unavailable.
`A4.1` is a diagnostic sample, not a change to A2 gold annotations or A4 metrics.
