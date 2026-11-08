"""Prompt-tuning harness for the RAKG extractor against the frozen gold.

Not the production adapter: this calls an OpenAI-compatible endpoint, recovers
exact document offsets from the returned surface forms (4B models cannot count
characters reliably), maps relation endpoints to mentions, and scores with the
same functions as the regex baseline.
"""
from __future__ import annotations

import json
import math
import re
from pathlib import Path
from typing import Callable

import requests

BOUNDARY = re.compile(r"(?<=[.!?])\s+|\n\s*\n")
FENCE = re.compile(r"^```(?:json)?\s*|\s*```$", re.IGNORECASE)

ALLOWED_TYPES = (
    "MaintenanceSystem, Component, Protocol, Procedure, Material, Organization, Person, Role, "
    "Product, Document, Standard, Metric, Measurement, Location, Time, Algorithm, DataStructure, "
    "Concept, Event, Problem"
)
ALLOWED_PREDICATES = (
    "has_component, part_of, tested_every, replaced_every, signs, records, documents, performed_on, "
    "logs, verifies, replaced_at, requires, repeats_every, applied_to, is_a, used_in, improves_over, "
    "discussed_in, same_as, seeks, visits, combines, implements, uses, has, related_to, computes, "
    "measures, obeys, matches"
)


def sentence_spans(text: str) -> list[tuple[int, int]]:
    spans = []
    cursor = 0
    for match in BOUNDARY.finditer(text):
        if match.start() > cursor and text[cursor:match.start()].strip():
            spans.append((cursor, match.start()))
        cursor = match.end()
    if cursor < len(text) and text[cursor:].strip():
        spans.append((cursor, len(text)))
    return spans


def sentence_chunks(text: str, max_tokens: int = 512, overlap: int = 1) -> list[tuple[str, int]]:
    spans = sentence_spans(text)
    chunks: list[tuple[str, int]] = []
    start = 0
    while start < len(spans):
        end = start
        tokens = 0
        while end < len(spans):
            sentence_tokens = max(1, math.ceil((spans[end][1] - spans[end][0]) / 4))
            if end > start and tokens + sentence_tokens > max_tokens:
                break
            tokens += sentence_tokens
            end += 1
            if sentence_tokens > max_tokens:
                break
        if end == start:
            end = start + 1
        raw = text[spans[start][0]:spans[end - 1][1]]
        leading = len(raw) - len(raw.lstrip())
        body = raw.strip()
        if body:
            chunks.append((body, spans[start][0] + leading))
        if end >= len(spans):
            break
        start = max(start + 1, end - overlap)
    return chunks


def parse_json(content: str) -> dict:
    content = FENCE.sub("", content.strip())
    start = content.find("{")
    end = content.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("no JSON object in model output")
    data = json.loads(content[start:end + 1], strict=False)
    if not isinstance(data, dict):
        raise ValueError("model output is not an object")
    return data


def call_llm(endpoint: str, model: str, system: str, text: str,
             max_tokens: int, temperature: float) -> dict:
    payload = {
        "model": model,
        "temperature": temperature,
        "max_tokens": max_tokens,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": "TEXT:\n" + text},
        ],
    }
    response = requests.post(endpoint.rstrip("/") + "/v1/chat/completions",
                             json=payload, timeout=180)
    response.raise_for_status()
    content = response.json()["choices"][0]["message"]["content"]
    return parse_json(content)


def _occurrences(haystack: str, needle: str) -> list[int]:
    if not needle:
        return []
    exact = [m.start() for m in re.finditer(re.escape(needle), haystack)]
    if exact:
        return exact
    return [m.start() for m in re.finditer(re.escape(needle), haystack, re.IGNORECASE)]


def _pick(occurrences: list[int], claimed: int) -> int:
    if not occurrences:
        raise ValueError("no occurrence")
    if claimed is None or claimed < 0:
        return occurrences[0]
    return min(occurrences, key=lambda index: abs(index - claimed))


def _guess_type(surface: str) -> str:
    lower = surface.lower()
    if re.search(r"\b(days?|hours?|weeks?|months?|years?|minutes?|seconds?)\b", lower):
        return "Time"
    if "%" in surface or re.search(r"\b\d+(\.\d+)?\b", surface):
        return "Metric"
    return "Concept"


def _normalize_predicate(predicate: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", (predicate or "").strip().lower()).strip("_")


def build_prediction(document_id: str, text: str, responses: list[tuple[dict, str, int]],
                     show_raw: bool = False) -> dict:
    entities: list[dict] = []
    seen_spans: dict[tuple[int, int], dict] = {}
    raw_relations: list[dict] = []

    def add_entity(surface: str, proposed_type: str, start: int, end: int) -> dict | None:
        key = (start, end)
        if key in seen_spans:
            return seen_spans[key]
        entity = {
            "mentionId": f"m{len(entities) + 1}",
            "surfaceForm": surface,
            "proposedType": proposed_type or "Concept",
            "charStart": start,
            "charEnd": end,
        }
        entities.append(entity)
        seen_spans[key] = entity
        return entity

    for response, body, base in responses:
        for raw in response.get("preEntities", []) or []:
            surface = str(raw.get("surfaceForm", "")).strip()
            if not surface:
                continue
            claimed = raw.get("charStart")
            claimed = int(claimed) if isinstance(claimed, (int, float)) else -1
            try:
                index = _pick(_occurrences(body, surface), claimed)
            except ValueError:
                if show_raw:
                    print(f"  skip entity not in chunk: {surface!r}")
                continue
            add_entity(surface, str(raw.get("proposedType", "Concept")), base + index,
                       base + index + len(surface))
        for raw in response.get("relations", []) or []:
            subject = str(raw.get("subjectForm", "")).strip()
            obj = str(raw.get("objectForm", "")).strip()
            predicate = _normalize_predicate(str(raw.get("predicate", "")))
            if not subject or not obj or not predicate or subject == obj:
                continue
            raw_relations.append({
                "subjectForm": subject,
                "objectForm": obj,
                "predicate": predicate,
                "evidenceQuote": str(raw.get("evidenceQuote", "")).strip(),
            })

    by_surface: dict[str, dict] = {}
    for entity in entities:
        by_surface.setdefault(entity["surfaceForm"].lower(), entity)

    def resolve(surface: str, evidence_quote: str) -> dict | None:
        found = by_surface.get(surface.lower())
        if found:
            return found
        span = None
        if evidence_quote:
            quote_at = text.find(evidence_quote)
            if quote_at >= 0:
                inside = text.find(surface, quote_at, quote_at + len(evidence_quote))
                if inside >= 0:
                    span = (inside, inside + len(surface))
        if span is None:
            occurrences = _occurrences(text, surface)
            if not occurrences:
                return None
            span = (occurrences[0], occurrences[0] + len(surface))
        entity = add_entity(surface, _guess_type(surface), span[0], span[1])
        by_surface.setdefault(surface.lower(), entity)
        return entity

    relations: list[dict] = []
    seen_relations: set[tuple] = set()
    for raw in raw_relations:
        subject = resolve(raw["subjectForm"], raw["evidenceQuote"])
        obj = resolve(raw["objectForm"], raw["evidenceQuote"])
        if subject is None or obj is None:
            continue
        if raw["evidenceQuote"]:
            quote_at = text.find(raw["evidenceQuote"])
        else:
            quote_at = -1
        if quote_at >= 0:
            evidence_start, evidence_end = quote_at, quote_at + len(raw["evidenceQuote"])
        else:
            evidence_start = min(subject["charStart"], obj["charStart"])
            evidence_end = max(subject["charEnd"], obj["charEnd"])
        key = (subject["mentionId"], raw["predicate"], obj["mentionId"],
               evidence_start, evidence_end)
        if key in seen_relations:
            continue
        seen_relations.add(key)
        relations.append({
            "subjectMentionId": subject["mentionId"],
            "predicate": raw["predicate"],
            "objectMentionId": obj["mentionId"],
            "evidenceChunkId": document_id,
            "evidenceCharStart": evidence_start,
            "evidenceCharEnd": evidence_end,
        })

    return {"preEntities": entities, "relations": relations}


def run(*, endpoint: str, model: str, prompt_path: Path, corpus: Path, gold: Path,
        docs: list[str], evaluate_document: Callable, aggregate: Callable,
        out_dir: Path, max_tokens: int = 2048, temperature: float = 0.0,
        limit: int | None = None, only: list[str] | None = None,
        show_raw: bool = False) -> dict:
    template = prompt_path.read_text(encoding="utf-8")
    system = (template.replace("{allowed_types}", ALLOWED_TYPES)
              .replace("{allowed_predicates}", ALLOWED_PREDICATES))
    selected = [doc for doc in docs if not only or doc in only]
    if limit:
        selected = selected[:limit]
    predictions: dict[str, dict] = {}
    rows = []
    print(f"LLM extraction: {endpoint} model={model} docs={len(selected)}")
    for gold_id in selected:
        gold_doc = json.loads((gold / f"{gold_id}.json").read_text(encoding="utf-8"))
        document_id = gold_doc["document_id"]
        text = (corpus / f"{document_id}.txt").read_text(encoding="utf-8")
        responses = []
        for chunk, base in sentence_chunks(text):
            try:
                parsed = call_llm(endpoint, model, system, chunk, max_tokens, temperature)
            except Exception as exc:  # noqa: BLE001 - tuning harness reports and continues
                print(f"  {document_id}: chunk call failed: {exc}")
                continue
            if show_raw:
                print(f"  raw {document_id}@{base}: {json.dumps(parsed)[:400]}")
            responses.append((parsed, chunk, base))
        prediction = build_prediction(document_id, text, responses, show_raw=show_raw)
        predictions[document_id] = prediction
        row = evaluate_document(gold_doc, prediction)
        rows.append(row)
        print(f"{gold_id} {document_id:28} pred={row['predicted_mentions']:3}e "
              f"{row['predicted_relations']:3}r  entity_F1={row['entity_overlap']['f1']:.3f} "
              f"rel_F1={row['relationship']['f1']:.3f}")

    summary = {
        "extractor": {
            "adapter": "LlmExtractionAdapter (tuning harness)",
            "endpoint": endpoint,
            "model": model,
            "prompt": str(prompt_path),
            "phase": "B (prompt tuning)",
        },
        "aggregate": aggregate(rows),
        "per_document": rows,
    }
    (out_dir / "llm-predictions.json").write_text(json.dumps(predictions, indent=2) + "\n", encoding="utf-8")
    (out_dir / "llm-baseline-result.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")

    agg = summary["aggregate"]
    print()
    print(f"entity(overlap) P={agg['entity_overlap']['precision']:.3f} "
          f"R={agg['entity_overlap']['recall']:.3f} F1={agg['entity_overlap']['f1']:.3f} "
          f"type={agg['type_accuracy']}")
    print(f"relationship P={agg['relationship']['precision']:.3f} "
          f"R={agg['relationship']['recall']:.3f} F1={agg['relationship']['f1']:.3f} "
          f"({agg['predicted_relations']} preds) evidence={agg['evidence_coverage']}")
    print(f"wrote {out_dir / 'llm-predictions.json'} and {out_dir / 'llm-baseline-result.json'}")
    return summary
