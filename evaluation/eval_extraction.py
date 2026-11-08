#!/usr/bin/env python3
"""Baseline extraction evaluation over the frozen gold (#558).

The extractor under test is the *current* deterministic producer in synflux:
`SynfluxConfig.annotationRules()` registers a single `KeywordAnnotationRule`
(definition `payment-detection`, keywords `invoice number`, `payment reference`,
`payment terms`). That rule matches a whole chunk and returns one TAG annotation;
it has no character offsets, entity types, resolution, or relationships.

This script faithfully reproduces that rule's semantics over each document
(treated as one chunk), aligns any predicted span to the gold by offsets, and
prints the metrics. A frozen-gold integrity check runs first.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CORPUS = ROOT / "corpus"
GOLD = ROOT / "gold"
MANIFEST = GOLD / "MANIFEST.sha256"

# Mirrors synflux SynfluxConfig.annotationRules() → KeywordAnnotationRule.
RULE = {
    "definition_id": "payment-detection",
    "definition_version": 4,
    "annotation_type": "TAG",
    "namespace": "billing",
    "name": "payment",
    "producer": "payment-rule-engine",
    "producer_version": "4.2",
    "keywords": ["invoice number", "payment reference", "payment terms"],
}
RULE_PATTERN = re.compile(
    r"(?i)\b(" + "|".join(re.escape(k) for k in RULE["keywords"]) + r")\b"
)

DOCS = ["doc-1", "doc-2", "doc-3", "doc-4", "doc-5"]


def verify_manifest() -> None:
    expected = {}
    for line in MANIFEST.read_text(encoding="utf-8").splitlines():
        if line.strip():
            digest, path = line.split(maxsplit=1)
            expected[path.strip()] = digest
    for path, digest in expected.items():
        actual = hashlib.sha256((ROOT.parent / path).read_bytes()).hexdigest()
        if actual != digest:
            raise SystemExit(f"gold manifest mismatch for {path}: {actual} != {digest}")
    print(f"manifest OK ({len(expected)} files)")


def predict(text: str) -> list[dict]:
    """The rule emits at most one TAG annotation per chunk; map it to its span."""
    match = RULE_PATTERN.search(text)
    if not match:
        return []
    return [{
        "char_start": match.start(),
        "char_end": match.end(),
        "surface_form": match.group(0),
        "proposed_type": RULE["annotation_type"],
        "resolution_status": "unresolved",  # the rule performs no resolution
        "canonical_id": None,
    }]


def overlaps(a_start, a_end, b_start, b_end) -> bool:
    return min(a_end, b_end) > max(a_start, b_start)


def match_entities(predicted, gold, mode):
    used_gold, pairs = set(), []
    for p in predicted:
        for i, g in enumerate(gold):
            if i in used_gold:
                continue
            hit = (p["char_start"] == g["char_start"] and p["char_end"] == g["char_end"]) \
                if mode == "exact" else overlaps(
                    p["char_start"], p["char_end"], g["char_start"], g["char_end"])
            if hit:
                used_gold.add(i)
                pairs.append((p, g))
                break
    tp = len(pairs)
    fp = len(predicted) - tp
    fn = len(gold) - tp
    precision = tp / (tp + fp) if (tp + fp) else 0.0
    recall = tp / (tp + fn) if (tp + fn) else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0
    return {"tp": tp, "fp": fp, "fn": fn, "precision": precision, "recall": recall, "f1": f1}, pairs


def evaluate_document(document_id: str, gold: dict) -> dict:
    text = (CORPUS / f"{document_id}.txt").read_text(encoding="utf-8")
    predicted = predict(text)
    exact, _ = match_entities(predicted, gold["entities"], "exact")
    overlap, overlap_pairs = match_entities(predicted, gold["entities"], "overlap")

    # Resolution accuracy over matched mentions: the rule never resolves anything.
    resolution_correct = sum(1 for p, g in overlap_pairs if p["resolution_status"] == g["resolution_status"])
    resolution_accuracy = resolution_correct / len(overlap_pairs) if overlap_pairs else None

    # The rule cannot emit relationships or evidence.
    gold_rel = len(gold["relationships"])
    relation_precision = 0.0 if predicted else None
    relation_recall = 0.0
    evidence_coverage = None

    return {
        "document_id": document_id,
        "gold_entities": len(gold["entities"]),
        "gold_relationships": gold_rel,
        "predicted_mentions": len(predicted),
        "rule_matched": bool(predicted),
        "entity_exact": exact,
        "entity_overlap": overlap,
        "resolution_accuracy": resolution_accuracy,
        "relationship": {"tp": 0, "fp": 0, "fn": gold_rel,
                         "precision": relation_precision, "recall": relation_recall},
        "evidence_coverage": evidence_coverage,
    }


def aggregate(rows: list[dict]) -> dict:
    gold_entities = sum(r["gold_entities"] for r in rows)
    gold_relations = sum(r["gold_relationships"] for r in rows)
    predicted = sum(r["predicted_mentions"] for r in rows)
    tp_exact = sum(r["entity_exact"]["tp"] for r in rows)
    tp_overlap = sum(r["entity_overlap"]["tp"] for r in rows)
    resolution_values = [r["resolution_accuracy"] for r in rows if r["resolution_accuracy"] is not None]
    return {
        "documents": len(rows),
        "gold_entities": gold_entities,
        "gold_relationships": gold_relations,
        "predicted_mentions": predicted,
        "entity_exact": {
            "tp": tp_exact, "fp": predicted - tp_exact, "fn": gold_entities - tp_exact,
            "precision": (tp_exact / predicted) if predicted else 0.0,
            "recall": (tp_exact / gold_entities) if gold_entities else 0.0,
            "f1": (2 * tp_exact / (predicted + gold_entities)) if (predicted + gold_entities) else 0.0,
        },
        "entity_overlap": {
            "tp": tp_overlap, "fp": predicted - tp_overlap, "fn": gold_entities - tp_overlap,
            "precision": (tp_overlap / predicted) if predicted else 0.0,
            "recall": (tp_overlap / gold_entities) if gold_entities else 0.0,
            "f1": (2 * tp_overlap / (predicted + gold_entities)) if (predicted + gold_entities) else 0.0,
        },
        "resolution_accuracy": (sum(resolution_values) / len(resolution_values)) if resolution_values else None,
        "relationship": {
            "recall": 0.0, "precision": None, "f1": 0.0,
            "note": "the rule emits no relationships",
        },
        "evidence_coverage": None,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-manifest", action="store_true", help="skip the frozen-gold hash check")
    args = parser.parse_args()

    if not args.skip_manifest:
        verify_manifest()

    rows = []
    for gold_id in DOCS:
        gold = json.loads((GOLD / f"{gold_id}.json").read_text(encoding="utf-8"))
        row = evaluate_document(gold["document_id"], gold)
        rows.append(row)
        print(f"{gold_id} {row['document_id']:28} gold={row['gold_entities']:2}e/"
              f"{row['gold_relationships']:2}r predicted={row['predicted_mentions']} "
              f"entity_recall={row['entity_overlap']['recall']:.2f} rel_recall={row['relationship']['recall']:.2f}")

    summary = {
        "extractor": RULE,
        "aggregate": aggregate(rows),
        "per_document": rows,
    }
    target = ROOT / "baseline-result.json"
    target.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")

    agg = summary["aggregate"]
    print()
    print(f"documents={agg['documents']} gold_entities={agg['gold_entities']} "
          f"gold_relationships={agg['gold_relationships']} predicted_mentions={agg['predicted_mentions']}")
    print(f"entity(overlap) P={agg['entity_overlap']['precision']:.3f} "
          f"R={agg['entity_overlap']['recall']:.3f} F1={agg['entity_overlap']['f1']:.3f}")
    print(f"resolution_accuracy={agg['resolution_accuracy']} "
          f"relationship_recall={agg['relationship']['recall']:.3f} "
          f"evidence_coverage={agg['evidence_coverage']}")
    print(f"wrote {target}")


if __name__ == "__main__":
    main()
