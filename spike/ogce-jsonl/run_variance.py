#!/usr/bin/env python3
"""Variance addendum for the OGCE JSONL slice.

- n=10 latency on the primary document (exploratory; not G-10 evidence)
- 3 seeds on the same segment
- 5 non-frozen DEV documents (text only; no labels)
- Tier 2 JSONL grammar enforcement probe

This script does not compute F1 and does not use the ADR-022 frozen gold.
"""
from __future__ import annotations

import json
import time
from pathlib import Path

import run_slice as rs

ROOT = Path(__file__).parent
PRIMARY = ROOT / "fixtures" / "sample-doc.md"
DEV_DOCS = [
    ("g7-dev-011", "relationship_heavy", ROOT / "fixtures" / "dev" / "g7-dev-011.txt"),
    ("g7-dev-013", "relationship_heavy", ROOT / "fixtures" / "dev" / "g7-dev-013.txt"),
    ("g7-dev-016", "cross_document_references", ROOT / "fixtures" / "dev" / "g7-dev-016.txt"),
    ("g7-dev-018", "cross_document_references", ROOT / "fixtures" / "dev" / "g7-dev-018.txt"),
    ("g7-dev-023", "tables_structured", ROOT / "fixtures" / "dev" / "g7-dev-023.txt"),
]
SCHEMA = json.loads(rs.SCHEMA_PATH.read_text())
PROMPT = rs.PROMPT_PATH.read_text()


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    idx = max(0, min(len(ordered) - 1, int(round((p / 100.0) * (len(ordered) - 1)))))
    return round(ordered[idx], 4)


def truncate_tokens(text: str, tokenizer, max_tokens: int = 3000) -> tuple[str, bool]:
    ids = tokenizer.encode(text, add_special_tokens=False)
    if len(ids) <= max_tokens:
        return text, False
    return tokenizer.decode(ids[:max_tokens]), True


def run_doc(doc_id: str, stratum: str, text: str, client, tokenizer, n_runs: int = 3) -> dict:
    count_tokens = lambda value: len(tokenizer.encode(value, add_special_tokens=False))
    text, truncated = truncate_tokens(text, tokenizer)
    chunker = rs.Chunker(text, count_tokens)
    chunks, segments = chunker.build()
    records: list[dict] = []
    calls: list[dict] = []
    for run in range(n_runs):
        for seg in segments:
            outcome = client.chat(seg.text)
            content, finish, usage = rs.content_of(outcome["response"])
            parsed = rs.parse_jsonl(content, seg.text, seg.start, SCHEMA)
            record = {
                "run": run,
                "segment": seg.index,
                "tokens": seg.tokens,
                "overlap": seg.overlap_tokens,
                "error": outcome["error"],
                "finish_reason": finish,
                "usage": usage,
                "latency_s": round(outcome["latency"], 4),
                "raw": content,
                "lines_total": parsed["lines_total"],
                "lines_accepted": len(parsed["accepted"]),
                "lines_quarantined": len(parsed["quarantined"]),
            }
            records.append(record)
            calls.append({key: record[key] for key in ("run", "segment", "error", "latency_s", "lines_total", "lines_accepted", "lines_quarantined")})
    first_run = [item for item in records if item["run"] == 0]
    target = rs.longest_response(first_run)
    experiments: dict = {}
    if target:
        seg = segments[target["segment"]]
        experiments["e1"] = rs.experiment_e1(target["raw"], seg.text, seg.start, SCHEMA)
        experiments["e2"] = rs.experiment_e2(target["raw"], seg.text, seg.start, SCHEMA)
        experiments["e3"] = rs.experiment_e3(target["raw"], seg.text, seg.start, SCHEMA)
        experiments["source_segment"] = target["segment"]
        experiments["source_lines"] = target["lines_total"]
    else:
        for key in ("e1", "e2", "e3"):
            experiments[key] = {"pass": False, "finding": "NO_USABLE_RESPONSE"}
    experiments["e5"] = rs.experiment_e5(chunks, segments)
    processed = len([item for item in first_run if item["error"] is None])
    returned = sum(item["lines_total"] for item in first_run)
    accepted = sum(item["lines_accepted"] for item in first_run)
    quarantined = sum(item["lines_quarantined"] for item in first_run)
    experiments["e4"] = rs.experiment_e4(len(segments), processed, returned, accepted, quarantined)
    latencies = [item["latency_s"] for item in calls if item["error"] is None]
    return {
        "doc_id": doc_id,
        "stratum": stratum,
        "truncated_to_3000_tokens": truncated,
        "doc_tokens": count_tokens(text),
        "chunks": len(chunks),
        "segments": len(segments),
        "segment_tokens": [seg.tokens for seg in segments],
        "segment_undersized": [
            {"segment": seg.index, "tokens": seg.tokens, "reason": seg.segment_undersized_reason}
            for seg in segments
            if seg.segment_undersized
        ],
        "calls": len(calls),
        "lines_returned": returned,
        "lines_accepted": accepted,
        "lines_quarantined": quarantined,
        "coverage": experiments["e4"],
        "experiments": experiments,
        "latency": {
            "n": len(latencies),
            "p50": percentile(latencies, 50),
            "p95": percentile(latencies, 95),
            "min": round(min(latencies), 4) if latencies else None,
            "max": round(max(latencies), 4) if latencies else None,
        },
        "latencies": latencies,
    }


def primary_runs(client, tokenizer, n: int = 10) -> dict:
    text = PRIMARY.read_text()
    count_tokens = lambda value: len(tokenizer.encode(value, add_special_tokens=False))
    chunker = rs.Chunker(text, count_tokens)
    chunks, segments = chunker.build()
    segment = segments[1]  # the clean 15-fact segment seen in the primary run
    latencies: list[float] = []
    hashes: list[str] = []
    formats: list[list[list[str]]] = []
    for _ in range(n):
        outcome = client.chat(segment.text)
        content, finish, usage = rs.content_of(outcome["response"])
        latencies.append(round(outcome["latency"], 4))
        hashes.append(rs.sha256(content))
        formats.append(rs.format_signature(content))
    seeds = []
    for seed in (42, 43, 44):
        outcome = client.chat(segment.text, extra={"seed": seed})
        content, finish, usage = rs.content_of(outcome["response"])
        seeds.append({
            "seed": seed,
            "output_sha256": rs.sha256(content),
            "format_equal_to_seed_42": rs.format_signature(content) == formats[0],
            "latency_s": round(outcome["latency"], 4),
        })
    grammar = 'root ::= line "\\n" line\nline ::= "{\\"type\\":\\"entity\\",\\"surface\\":\\"Acme Corporation\\",\\"class\\":\\"Organization\\"}"'
    expected = '{"type":"entity","surface":"Acme Corporation","class":"Organization"}'
    grammar_outcome = client.chat(
        "Acme Corporation is a company. Elena Torres works for Acme Corporation.",
        extra={"structured_outputs": {"grammar": grammar}},
        max_tokens=128,
    )
    grammar_content, _, _ = rs.content_of(grammar_outcome["response"])
    return {
        "segment_index": segment.index,
        "segment_tokens": segment.tokens,
        "n": n,
        "latency": {
            "p50": percentile(latencies, 50),
            "p95": percentile(latencies, 95),
            "min": min(latencies) if latencies else None,
            "max": max(latencies) if latencies else None,
        },
        "latencies": latencies,
        "unique_output_hashes": len(set(hashes)),
        "format_signatures_equal": all(item == formats[0] for item in formats),
        "seed_runs": seeds,
        "tier2_jsonl_grammar": {
            "http_ok": grammar_outcome["error"] is None,
            "error": grammar_outcome["error"],
            "expected_two_lines": expected + "\n" + expected,
            "observed": grammar_content,
            "enforced": grammar_content.strip() == expected + "\n" + expected,
        },
    }


def render(results: dict) -> str:
    lines = [
        "# OGCE JSONL slice — variance addendum",
        "",
        f"**Run:** {results['started_at']}  ",
        "**Scope:** exploratory contract variance; not F1, not G-10 p95, not profile qualification.",
        "",
        "## Primary document — n=10 latency",
        "",
        f"- Segment {results['primary']['segment_index']} ({results['primary']['segment_tokens']} tokens)",
        f"- p50 **{results['primary']['latency']['p50']} s**, p95 **{results['primary']['latency']['p95']} s** (min {results['primary']['latency']['min']}, max {results['primary']['latency']['max']})",
        f"- Unique output hashes: {results['primary']['unique_output_hashes']}; format signatures equal: {results['primary']['format_signatures_equal']}",
        f"- Seed runs: {json.dumps(results['primary']['seed_runs'])}",
        f"- Tier 2 JSONL grammar enforced: **{results['primary']['tier2_jsonl_grammar']['enforced']}**",
        "",
        "## DEV document runs (text only; no labels)",
        "",
        "| Doc | Stratum | Tokens | Chunks | Segments | Accepted | Quarantined | E1 | E2 | E3 | E5 | p50 | p95 |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for item in results["documents"]:
        exp = item["experiments"]
        lines.append(
            f"| {item['doc_id']} | {item['stratum']} | {item['doc_tokens']} | {item['chunks']} | {item['segments']} | "
            f"{item['lines_accepted']} | {item['lines_quarantined']} | {exp['e1'].get('pass')} | {exp['e2'].get('pass')} | "
            f"{exp['e3'].get('pass')} | {exp['e5'].get('pass')} | {item['latency']['p50']} | {item['latency']['p95']} |"
        )
    lines += [
        "",
        f"**Aggregate latency:** p50 **{results['aggregate_latency']['p50']} s**, p95 **{results['aggregate_latency']['p95']} s** over {results['aggregate_latency']['n']} calls.",
        "",
        "## Limitations",
        "",
        "- Five non-frozen DEV documents, not the full 25-document matrix. The frozen ADR-022 five are excluded.",
        "- DEV labels are not used; no F1, gate, or profile claim is made.",
        "- The 3-class/2-relation slice schema does not match every DEV document's type vocabulary; quarantine counts reflect that mismatch.",
        "- n=10 / n=3 latencies are exploratory. G-10 still requires a pinned benchmark protocol, repeated runs, and review on production modules.",
        "- E6 gold-span alignment is deferred: `evaluation/dev25/README.md`, `gold-release.md`, and `manifest.tsv` disagree on the 20 DEV annotations.",
        "",
    ]
    return "\n".join(lines)


def main() -> int:
    tokenizer = rs.load_tokenizer("/models")
    client = rs.ModelClient("http://127.0.0.1:8000", "qwen3-8b-fp8", PROMPT)
    started = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    primary = primary_runs(client, tokenizer)
    documents = []
    all_latencies: list[float] = list(primary.get("latencies", []))
    for doc_id, stratum, path in DEV_DOCS:
        print(f"running {doc_id} ({stratum})", flush=True)
        item = run_doc(doc_id, stratum, path.read_text(), client, tokenizer)
        documents.append(item)
        all_latencies.extend(item.get("latencies", []))
    results = {
        "started_at": started,
        "primary": primary,
        "documents": documents,
        "aggregate_latency": {
            "n": len(all_latencies),
            "p50": percentile(all_latencies, 50),
            "p95": percentile(all_latencies, 95),
            "note": "Exploratory across the primary n=10 and five DEV documents × 3 passes; not G-10 evidence.",
        },
    }
    out = Path("/spike/variance")
    out.mkdir(parents=True, exist_ok=True)
    (out / "variance.json").write_text(json.dumps(results, indent=2) + "\n")
    (out / "variance-report.md").write_text(render(results))
    print(f"wrote {out}/variance.json and {out}/variance-report.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
