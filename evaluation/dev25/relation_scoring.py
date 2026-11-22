"""Relation scoring with consistent entity-ID -> normalized-surface resolution."""
from __future__ import annotations
import re

def norm_surface(value):
    return re.sub(r"\s+", " ", (value or "").strip()).lower()

def norm_predicate(value):
    s = (value or "").strip()
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", s)
    return re.sub(r"[^a-z0-9]+", "_", s.lower()).strip("_")

def triples(entities, relations):
    """Return (subject_surface, predicate, object_surface) triples.

    Unresolved endpoints are skipped and counted separately by the caller.
    """
    by_id = {e["mention_id"]: norm_surface(e["surface_form"]) for e in entities}
    out = set()
    for r in relations:
        s = by_id.get(r.get("subject_id"))
        o = by_id.get(r.get("object_id"))
        if s is None or o is None or s == o:
            continue
        out.add((s, norm_predicate(r.get("predicate")), o))
    return out

def score_relations(pred_entities, pred_relations, gold_entities, gold_relations):
    P = triples(pred_entities, pred_relations)
    G = triples(gold_entities, gold_relations)
    tp = len(P & G)
    precision = tp / len(P) if P else 0.0
    recall = tp / len(G) if G else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    reverse = {(o, p, s) for s, p, o in G}
    direction_errors = sum(1 for t in P if t not in G and t in reverse)
    return {
        "tp": tp, "predicted": len(P), "gold": len(G),
        "precision": precision, "recall": recall, "f1": f1,
        "direction_errors": direction_errors,
        "missing_endpoint_relations": sum(
            1 for r in pred_relations
            if r.get("subject_id") not in {e["mention_id"] for e in pred_entities}
            or r.get("object_id") not in {e["mention_id"] for e in pred_entities}
        ),
    }
