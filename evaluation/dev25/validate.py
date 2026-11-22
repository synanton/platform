#!/usr/bin/env python3
"""Validate the GPU-7 DEV-25 manifest and any present gold annotations.

Read-only. No model inference. Reports coverage, unresolved cases, and any
schema/checksum failures without modifying data.
"""
from __future__ import annotations
import hashlib, json, sys, csv
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEV = ROOT / 'evaluation' / 'dev25'

def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()

def load_manifest():
    rows = list(csv.DictReader(open(DEV / 'manifest.tsv'), delimiter='\t'))
    assert len(rows) == 25, f'expected 25 manifest rows, got {len(rows)}'
    return rows

def validate_gold(path: Path, text: str) -> list[str]:
    errs = []
    d = json.loads(path.read_text())
    ents = {e['mention_id']: e for e in d.get('entities', [])}
    for e in ents.values():
        s, e2 = int(e['char_start']), int(e['char_end'])
        if e2 < s or e2 > len(text):
            errs.append(f'{path.name}: bad entity span {e["mention_id"]} {s}:{e2}')
        elif text[s:e2] != e['surface_form']:
            errs.append(f'{path.name}: surface mismatch {e["mention_id"]}')
        if e.get('resolution_status') in ('ambiguous', 'unresolvable') and e.get('canonical_id'):
            errs.append(f'{path.name}: {e["mention_id"]} unresolved but has canonical_id')
    for r in d.get('relationships', []):
        if r['subject_id'] not in ents or r['object_id'] not in ents:
            errs.append(f'{path.name}: relation endpoint missing {r}')
        s, e2 = int(r['evidence_span_start']), int(r['evidence_span_end'])
        if e2 < s or e2 > len(text):
            errs.append(f'{path.name}: bad evidence span for {r["subject_id"]}->{r["object_id"]}')
    return errs

def main() -> int:
    rows = load_manifest()
    errors = []
    validated = 0
    for r in rows:
        p = ROOT / r['source_path']
        if not p.exists():
            errors.append(f'{r["doc_id"]}: missing source {p}')
            continue
        actual = sha(p)
        if actual != r['sha256']:
            errors.append(f'{r["doc_id"]}: checksum mismatch')
        if r['annotation_status'] == 'VALIDATED_GOLD':
            gold = ROOT / r['gold_record']
            if not gold.exists():
                errors.append(f'{r["doc_id"]}: gold record missing')
            else:
                tp = DEV / 'extracted' / f'{r["doc_id"]}.txt'
                text = tp.read_text(encoding='utf-8', errors='replace') if tp.exists() else p.read_text(encoding='utf-8', errors='replace')
                errors += validate_gold(gold, text)
                validated += 1
    unlabeled = sum(r['annotation_status'] == 'UNLABELED' for r in rows)
    print(f'manifest=25 validated_gold={validated} unlabeled={unlabeled} errors={len(errors)}')
    for e in errors:
        print('  ERR', e)
    return 1 if errors else 0

if __name__ == '__main__':
    sys.exit(main())
