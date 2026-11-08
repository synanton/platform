#!/usr/bin/env python3
"""Extraction baseline evaluation over the frozen gold (#558).

Runs the Phase A extraction adapter (regex, `ExtractionEngineAdapter`) through
its CLI, then aligns its candidate mentions/relations to the gold by character
offsets. The gold manifest is verified first; a reportable run must pass it.

The adapter is deliberately generic (identifiers, metrics, quoted/capitalized
terms, cue-based relations) and performs no resolution. See
docs/planning/extraction-gold-schema.md and docs/research/extraction-baseline-report.md.
"""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PLATFORM = ROOT.parent
CORPUS = ROOT / "corpus"
GOLD = ROOT / "gold"
MANIFEST = GOLD / "MANIFEST.sha256"
PREDICTIONS_DOC = ROOT / "regex-predictions.json"
PREDICTIONS_CHUNKED = ROOT / "regex-predictions-chunked.json"
CLASSES = PLATFORM / "java" / "extraction-engine" / "build" / "classes" / "java" / "main"

DOCS = ["doc-1", "doc-2", "doc-3", "doc-4", "doc-5"]


def verify_manifest() -> None:
    expected = {}
    for line in MANIFEST.read_text(encoding="utf-8").splitlines():
        if line.strip():
            digest, path = line.split(maxsplit=1)
            expected[path.strip()] = digest
    for path, digest in expected.items():
        actual = hashlib.sha256((PLATFORM / path).read_bytes()).hexdigest()
        if actual != digest:
            raise SystemExit(f"gold manifest mismatch for {path}: {actual} != {digest}")
    print(f"manifest OK ({len(expected)} files)")


def run_java_adapter(sentence_chunks: bool = False) -> dict:
    if not CLASSES.exists():
        print("building java/extraction-engine ...")
        subprocess.run([str(PLATFORM / "gradlew"), "-q", ":java:extraction-engine:classes"],
                       cwd=PLATFORM, check=True)
    out = PREDICTIONS_CHUNKED if sentence_chunks else PREDICTIONS_DOC
    command = [
        "java", "-cp", str(CLASSES),
        "org.synanton.extraction.engine.cli.ExtractionCli",
        "--corpus-dir", str(CORPUS),
        "--out", str(out),
    ]
    if sentence_chunks:
        command += ["--sentence-chunks", "--max-tokens", "512"]
    subprocess.run(command, check=True)
    return json.loads(out.read_text(encoding="utf-8"))


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


def evaluate_document(gold: dict, prediction: dict) -> dict:
    predicted = [{
        "mention_id": e["mentionId"],
        "char_start": e["charStart"],
        "char_end": e["charEnd"],
        "surface_form": e["surfaceForm"],
        "proposed_type": e["proposedType"],
    } for e in prediction.get("preEntities", [])]
    predicted_by_id = {e["mention_id"]: e for e in predicted}

    exact, _ = match_entities(predicted, gold["entities"], "exact")
    overlap, overlap_pairs = match_entities(predicted, gold["entities"], "overlap")
    pred_to_gold = {id(p): g for p, g in overlap_pairs}
    type_correct = sum(1 for p, g in overlap_pairs if p["proposed_type"] == g["proposed_type"])
    type_accuracy = type_correct / len(overlap_pairs) if overlap_pairs else None

    predicted_relations = prediction.get("relations", [])
    dedup = {}
    for r in predicted_relations:
        key = (r["subjectMentionId"], r["predicate"], r["objectMentionId"],
               r["evidenceCharStart"], r["evidenceCharEnd"])
        dedup[key] = r
    predicted_relations = list(dedup.values())

    used_gold_rel = set()
    tp = 0
    evidence_hits = 0
    for r in predicted_relations:
        subject = predicted_by_id.get(r["subjectMentionId"])
        obj = predicted_by_id.get(r["objectMentionId"])
        if subject is None or obj is None:
            continue
        gs, go = pred_to_gold.get(id(subject)), pred_to_gold.get(id(obj))
        if gs is None or go is None:
            continue
        for i, gr in enumerate(gold["relationships"]):
            if i in used_gold_rel:
                continue
            if (gr["subject_id"] == gs["mention_id"] and gr["object_id"] == go["mention_id"]
                    and gr["predicate"] == r["predicate"]):
                used_gold_rel.add(i)
                tp += 1
                if overlaps(r["evidenceCharStart"], r["evidenceCharEnd"],
                            gr["evidence_span_start"], gr["evidence_span_end"]):
                    evidence_hits += 1
                break
    fp = len(predicted_relations) - tp
    fn = len(gold["relationships"]) - tp
    precision = tp / (tp + fp) if (tp + fp) else 0.0
    recall = tp / (tp + fn) if (tp + fn) else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0

    return {
        "document_id": gold["document_id"],
        "gold_entities": len(gold["entities"]),
        "gold_relationships": len(gold["relationships"]),
        "predicted_mentions": len(predicted),
        "predicted_relations": len(predicted_relations),
        "entity_exact": exact,
        "entity_overlap": overlap,
        "type_accuracy": type_accuracy,
        "resolution_accuracy": None,  # Phase A performs no resolution
        "relationship": {"tp": tp, "fp": fp, "fn": fn,
                         "precision": precision, "recall": recall, "f1": f1},
        "evidence_coverage": (evidence_hits / tp) if tp else None,
    }


def aggregate(rows: list[dict]) -> dict:
    def ratio(key, denom):
        return None

    gold_entities = sum(r["gold_entities"] for r in rows)
    gold_relations = sum(r["gold_relationships"] for r in rows)
    predicted_entities = sum(r["predicted_mentions"] for r in rows)
    predicted_relations = sum(r["predicted_relations"] for r in rows)
    e_exact = sum(r["entity_exact"]["tp"] for r in rows)
    e_overlap = sum(r["entity_overlap"]["tp"] for r in rows)
    r_tp = sum(r["relationship"]["tp"] for r in rows)
    type_values = [r["type_accuracy"] for r in rows if r["type_accuracy"] is not None]
    evidence_values = [r["evidence_coverage"] for r in rows if r["evidence_coverage"] is not None]

    def prf(tp, predicted, actual):
        precision = tp / predicted if predicted else 0.0
        recall = tp / actual if actual else 0.0
        f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0
        return {"tp": tp, "fp": predicted - tp, "fn": actual - tp,
                "precision": precision, "recall": recall, "f1": f1}

    return {
        "documents": len(rows),
        "gold_entities": gold_entities,
        "gold_relationships": gold_relations,
        "predicted_mentions": predicted_entities,
        "predicted_relations": predicted_relations,
        "entity_exact": prf(e_exact, predicted_entities, gold_entities),
        "entity_overlap": prf(e_overlap, predicted_entities, gold_entities),
        "type_accuracy": (sum(type_values) / len(type_values)) if type_values else None,
        "resolution_accuracy": None,
        "relationship": prf(r_tp, predicted_relations, gold_relations),
        "evidence_coverage": (sum(evidence_values) / len(evidence_values)) if evidence_values else None,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-manifest", action="store_true", help="skip the frozen-gold hash check")
    args = parser.parse_args()

    if not args.skip_manifest:
        verify_manifest()

    summaries = {}
    for label, sentence_chunks in (("document_level", False), ("sentence_chunked", True)):
        predictions = run_java_adapter(sentence_chunks)
        rows = []
        print(f"\n[{label}]")
        for gold_id in DOCS:
            gold = json.loads((GOLD / f"{gold_id}.json").read_text(encoding="utf-8"))
            prediction = predictions.get(gold["document_id"], {"preEntities": [], "relations": []})
            row = evaluate_document(gold, prediction)
            rows.append(row)
            print(f"{gold_id} {row['document_id']:28} pred={row['predicted_mentions']:3}e "
                  f"{row['predicted_relations']:3}r  entity_F1={row['entity_overlap']['f1']:.3f} "
                  f"rel_F1={row['relationship']['f1']:.3f}")
        agg = aggregate(rows)
        summaries[label] = {"aggregate": agg, "per_document": rows}
        print(f"{label} entity(overlap) P={agg['entity_overlap']['precision']:.3f} "
              f"R={agg['entity_overlap']['recall']:.3f} F1={agg['entity_overlap']['f1']:.3f} "
              f"type={agg['type_accuracy']}  relationship F1={agg['relationship']['f1']:.3f} "
              f"({agg['predicted_relations']} preds)")

    summary = {
        "extractor": {
            "adapter": "RegexExtractorAdapter",
            "contract": "org.synanton.extraction.engine.ExtractionEngineAdapter",
            "phase": "A (deterministic baseline)",
        },
        "document_level": summaries["document_level"],
        "sentence_chunked": summaries["sentence_chunked"],
    }
    (ROOT / "baseline-result.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    agg = summary["sentence_chunked"]["aggregate"]
    print()
    print(f"wrote {ROOT / 'baseline-result.json'} (sentence_chunked primary, document_level reference)")


if __name__ == "__main__":
    main()
