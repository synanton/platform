"""Pure offline validators for chunking and evidence-grounded extraction tests."""
from __future__ import annotations
import re, math

def norm_surface(v): return re.sub(r"\s+", " ", (v or "").strip()).lower()
def norm_predicate(v):
    s = (v or "").strip()
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", s)
    return re.sub(r"[^a-z0-9]+", "_", s.lower()).strip("_")

def estimate_chunks(text, target_chars=8000, overlap_chars=200):
    if target_chars <= 0: raise ValueError('target_chars must be positive')
    step = max(1, target_chars - max(0, overlap_chars))
    return [start for start in range(0, len(text), step)] or [0]

def validate_entities(text, entities, allowed_types):
    errors=[]
    for e in entities:
        mid=e.get('mention_id','?'); s=e.get('char_start'); t=e.get('char_end')
        if not isinstance(s,int) or not isinstance(t,int) or s<0 or t<=s or t>len(text):
            errors.append(f'{mid}: invalid span {s}:{t}'); continue
        if text[s:t] != e.get('surface_form'):
            errors.append(f'{mid}: surface mismatch')
        if e.get('proposed_type') not in allowed_types:
            errors.append(f'{mid}: type not allowed')
        if e.get('resolution_status') in ('ambiguous','unresolvable') and e.get('canonical_id'):
            errors.append(f'{mid}: unresolved with canonical_id')
    return errors

def validate_relations(text, entities, relations, allowed_predicates):
    ids={e.get('mention_id') for e in entities}; errors=[]
    for r in relations:
        if r.get('subject_id') not in ids: errors.append('missing subject endpoint')
        if r.get('object_id') not in ids: errors.append('missing object endpoint')
        if r.get('subject_id') == r.get('object_id'): errors.append('self relation')
        if norm_predicate(r.get('predicate')) not in allowed_predicates: errors.append('predicate not allowed')
        s,e=r.get('evidence_span_start'),r.get('evidence_span_end')
        if not isinstance(s,int) or not isinstance(e,int) or s<0 or e<=s or e>len(text):
            errors.append('invalid evidence span')
    return errors

def reject_unsupported_relations(text, entities, relations, allowed_predicates):
    """Return only relations with known endpoints, allowed predicate and valid evidence."""
    ids={e.get('mention_id') for e in entities}; out=[]
    for r in relations:
        if r.get('subject_id') not in ids or r.get('object_id') not in ids: continue
        if r.get('subject_id')==r.get('object_id'): continue
        if norm_predicate(r.get('predicate')) not in allowed_predicates: continue
        s,e=r.get('evidence_span_start'),r.get('evidence_span_end')
        if not isinstance(s,int) or not isinstance(e,int) or s<0 or e<=s or e>len(text): continue
        out.append(r)
    return out
