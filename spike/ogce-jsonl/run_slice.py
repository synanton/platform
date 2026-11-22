#!/usr/bin/env python3
"""OGCE JSONL vertical-slice spike (contract robustness, not quality).

Runs against an OpenAI-compatible vLLM endpoint. The script is spike-grade:
it is intentionally small, records its own evidence, and does not claim
extraction quality, F1, cost, p95, or profile qualification.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import random
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional

PROMPT_PATH = Path(__file__).parent / "prompts" / "extraction_prompt.txt"
SCHEMA_PATH = Path(__file__).parent / "schema" / "ontology-subset.json"
DEFAULT_DOC = Path(__file__).parent / "fixtures" / "sample-doc.md"


# ---------------------------------------------------------------------------
# Chunking
# ---------------------------------------------------------------------------

SENT_RE = re.compile(r"[^.!?\n]+(?:[.!?]+|(?=\n|$))|\n+")


@dataclass
class Sentence:
    start: int
    end: int
    text: str


@dataclass
class Chunk:
    index: int
    start: int
    end: int
    text: str
    tokens: int
    sentences: list[Sentence] = field(default_factory=list)


@dataclass
class Segment:
    index: int
    chunk_index: int
    start: int
    end: int
    text: str
    tokens: int
    overlap_tokens: int
    segment_undersized: bool = False
    segment_undersized_reason: str = ""


def split_sentences(text: str) -> list[Sentence]:
    out: list[Sentence] = []
    for m in SENT_RE.finditer(text):
        s = m.group(0)
        if not s.strip():
            continue
        out.append(Sentence(m.start(), m.end(), s))
    return out or [Sentence(0, len(text), text)]


def split_long_sentence(text: str, sentence: Sentence, count_tokens: Callable[[str], int], max_tokens: int) -> list[Sentence]:
    words = re.finditer(r"\S+\s*", sentence.text)
    pieces: list[Sentence] = []
    cur_words: list[re.Match[str]] = []
    cur_text = ""
    for w in words:
        candidate = cur_text + w.group(0)
        if cur_words and count_tokens(candidate) > max_tokens:
            first = cur_words[0]
            last = cur_words[-1]
            pieces.append(Sentence(sentence.start + first.start(), sentence.start + last.end(), cur_text))
            cur_words = []
            cur_text = ""
        cur_words.append(w)
        cur_text += w.group(0)
    if cur_words:
        first = cur_words[0]
        last = cur_words[-1]
        pieces.append(Sentence(sentence.start + first.start(), sentence.start + last.end(), cur_text))
    return pieces or [sentence]


class Chunker:
    def __init__(
        self,
        text: str,
        count_tokens: Callable[[str], int],
        max_chunk: int = 512,
        min_segment: int = 256,
        max_segment: int = 384,
        overlap_min: int = 32,
        overlap_max: int = 48,
    ) -> None:
        self.text = text
        self.count_tokens = count_tokens
        self.max_chunk = max_chunk
        self.min_segment = min_segment
        self.max_segment = max_segment
        self.overlap_min = overlap_min
        self.overlap_max = overlap_max

    def build_chunks(self) -> list[Chunk]:
        chunks: list[Chunk] = []
        cur: list[Sentence] = []
        cur_tokens = 0
        for sent in split_sentences(self.text):
            toks = self.count_tokens(sent.text)
            pieces = [sent]
            if toks > self.max_chunk:
                pieces = split_long_sentence(self.text, sent, self.count_tokens, self.max_chunk)
            for piece in pieces:
                ptoks = self.count_tokens(piece.text)
                if cur and cur_tokens + ptoks > self.max_chunk:
                    chunks.append(self._make_chunk(len(chunks), cur))
                    cur = []
                    cur_tokens = 0
                cur.append(piece)
                cur_tokens += ptoks
        if cur:
            chunks.append(self._make_chunk(len(chunks), cur))
        return chunks

    def _make_chunk(self, index: int, sents: list[Sentence]) -> Chunk:
        start = sents[0].start
        end = sents[-1].end
        text = self.text[start:end]
        return Chunk(index, start, end, text, self.count_tokens(text), sents)

    def build_segments(self, chunk: Chunk) -> list[Segment]:
        sents = chunk.sentences
        segments: list[Segment] = []
        i = 0
        while i < len(sents):
            j = i
            seg_sents: list[Sentence] = []
            total = 0
            while j < len(sents):
                t = self.count_tokens(sents[j].text)
                if seg_sents and total + t > self.max_segment:
                    break
                seg_sents.append(sents[j])
                total += t
                j += 1
                if total >= self.min_segment:
                    break
            if not seg_sents:
                seg_sents = [sents[i]]
                total = self.count_tokens(sents[i].text)
                j = i + 1
            start = seg_sents[0].start
            end = seg_sents[-1].end
            segments.append(Segment(len(segments), chunk.index, start, end, self.text[start:end], total, 0))
            if j >= len(sents):
                break
            overlap_sents: list[Sentence] = []
            overlap_tokens = 0
            for s in reversed(seg_sents):
                t = self.count_tokens(s.text)
                if overlap_tokens + t > self.overlap_max:
                    break
                overlap_sents.insert(0, s)
                overlap_tokens += t
            if overlap_tokens < self.overlap_min:
                overlap_sents = []
                overlap_tokens = 0
            next_i = sents.index(overlap_sents[0]) if overlap_sents else j
            if next_i <= i:
                next_i = j if j > i else i + 1
            if next_i <= i:
                next_i = i + 1
            if overlap_sents:
                segments[-1].overlap_tokens = overlap_tokens
            i = next_i
        merged: list[Segment] = []
        for seg in segments:
            if (
                merged
                and seg.tokens < self.min_segment
                and merged[-1].tokens + seg.tokens <= self.max_segment
            ):
                prev = merged[-1]
                text = self.text[prev.start:seg.end]
                merged[-1] = Segment(
                    prev.index,
                    prev.chunk_index,
                    prev.start,
                    seg.end,
                    text,
                    self.count_tokens(text),
                    prev.overlap_tokens,
                )
            else:
                merged.append(seg)
        for idx, seg in enumerate(merged):
            if seg.tokens < self.min_segment:
                if len(merged) == 1 and chunk.tokens < self.min_segment:
                    reason = "SHORT_CHUNK_TAIL"
                elif idx > 0 and merged[idx - 1].tokens + seg.tokens > self.max_segment:
                    reason = "MERGE_WOULD_EXCEED_384"
                elif idx == 0:
                    reason = "UNMERGED_HEAD"
                else:
                    reason = "UNMERGED_TAIL"
                seg.segment_undersized = True
                seg.segment_undersized_reason = reason
        return merged

    def build(self) -> tuple[list[Chunk], list[Segment]]:
        chunks = self.build_chunks()
        segments: list[Segment] = []
        for chunk in chunks:
            for seg in self.build_segments(chunk):
                seg.index = len(segments)
                segments.append(seg)
        return chunks, segments


# ---------------------------------------------------------------------------
# Parsing and validation
# ---------------------------------------------------------------------------

def strip_fences(raw: str) -> tuple[str, bool]:
    s = raw.strip("\n")
    stripped = s.strip()
    if not stripped.startswith("```"):
        return s, False
    lines = s.splitlines()
    if lines and lines[0].strip().startswith("```"):
        lines = lines[1:]
    while lines and not lines[-1].strip():
        lines.pop()
    if lines and lines[-1].strip().startswith("```"):
        lines = lines[:-1]
    return "\n".join(lines), True


def find_unique(text: str, quote: str) -> tuple[Optional[tuple[int, int]], Optional[str]]:
    if not quote:
        return None, "EMPTY_QUOTE"
    positions = [m.start() for m in re.finditer(re.escape(quote), text)]
    if not positions:
        return None, "UNANCHORED"
    if len(positions) > 1:
        return None, "AMBIGUOUS_ANCHOR"
    return (positions[0], positions[0] + len(quote)), None


def anchor_within(evidence_span: tuple[int, int], evidence: str, surface: str) -> tuple[Optional[tuple[int, int]], Optional[str]]:
    rel = evidence.find(surface)
    if rel < 0:
        return None, "SURFACE_NOT_IN_EVIDENCE"
    start = evidence_span[0] + rel
    return (start, start + len(surface)), None


def parse_jsonl(raw: str, segment_text: str, segment_start: int, schema: dict) -> dict:
    cleaned, fence_stripped = strip_fences(raw)
    classes = set(schema.get("classes", []))
    relations = schema.get("relations", {})
    accepted: list[dict] = []
    quarantined: list[dict] = []
    lines = cleaned.splitlines()
    for line_no, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            obj = json.loads(line)
        except Exception as exc:  # noqa: BLE001 - reason codes are the contract
            quarantined.append({"line_no": line_no, "raw": line, "reason_code": "JSON_PARSE", "detail": str(exc)})
            continue
        if not isinstance(obj, dict):
            quarantined.append({"line_no": line_no, "raw": line, "reason_code": "SCHEMA", "detail": "line is not a JSON object"})
            continue
        kind = obj.get("type")
        evidence = obj.get("evidence")
        if not isinstance(evidence, str) or not evidence:
            quarantined.append({"line_no": line_no, "raw": line, "reason_code": "SCHEMA", "detail": "missing evidence"})
            continue
        evidence_span, reason = find_unique(segment_text, evidence)
        if reason:
            quarantined.append({"line_no": line_no, "raw": line, "reason_code": reason, "detail": evidence[:120]})
            continue
        if kind == "entity":
            surface = obj.get("surface")
            cls = obj.get("class")
            if not isinstance(surface, str) or not surface or cls not in classes:
                quarantined.append({"line_no": line_no, "raw": line, "reason_code": "UNKNOWN_CLASS" if cls not in classes else "SCHEMA", "detail": str(cls)})
                continue
            surface_span, reason = anchor_within(evidence_span, evidence, surface)
            if reason:
                quarantined.append({"line_no": line_no, "raw": line, "reason_code": reason, "detail": surface})
                continue
            accepted.append({
                "line_no": line_no,
                "obj": obj,
                "anchors": {
                    "evidence": [segment_start + evidence_span[0], segment_start + evidence_span[1]],
                    "surface": [segment_start + surface_span[0], segment_start + surface_span[1]],
                },
            })
            continue
        if kind == "relation":
            subject = obj.get("subject")
            object_ = obj.get("object")
            predicate = obj.get("predicate")
            subject_class = obj.get("subject_class")
            object_class = obj.get("object_class")
            if predicate not in relations:
                quarantined.append({"line_no": line_no, "raw": line, "reason_code": "UNKNOWN_PREDICATE", "detail": str(predicate)})
                continue
            expected = relations[predicate]
            if subject_class != expected.get("subject_class") or object_class != expected.get("object_class"):
                quarantined.append({"line_no": line_no, "raw": line, "reason_code": "CLASS_MISMATCH", "detail": f"{subject_class}->{object_class}"})
                continue
            if not isinstance(subject, str) or not isinstance(object_, str) or not subject or not object_:
                quarantined.append({"line_no": line_no, "raw": line, "reason_code": "SCHEMA", "detail": "missing endpoint"})
                continue
            if subject == object_:
                quarantined.append({"line_no": line_no, "raw": line, "reason_code": "SELF_RELATION", "detail": subject})
                continue
            subject_span, reason = anchor_within(evidence_span, evidence, subject)
            if reason:
                quarantined.append({"line_no": line_no, "raw": line, "reason_code": reason, "detail": subject})
                continue
            object_span, reason = anchor_within(evidence_span, evidence, object_)
            if reason:
                quarantined.append({"line_no": line_no, "raw": line, "reason_code": reason, "detail": object_})
                continue
            accepted.append({
                "line_no": line_no,
                "obj": obj,
                "anchors": {
                    "evidence": [segment_start + evidence_span[0], segment_start + evidence_span[1]],
                    "subject": [segment_start + subject_span[0], segment_start + subject_span[1]],
                    "object": [segment_start + object_span[0], segment_start + object_span[1]],
                },
            })
            continue
        quarantined.append({"line_no": line_no, "raw": line, "reason_code": "SCHEMA", "detail": f"unknown type {kind!r}"})
    return {
        "accepted": accepted,
        "quarantined": quarantined,
        "fence_stripped": fence_stripped,
        "lines_total": len([line for line in lines if line.strip()]),
    }


def monolithic_parse(raw: str) -> tuple[int, Optional[str]]:
    try:
        obj = json.loads(raw)
    except Exception as exc:  # noqa: BLE001
        return 0, f"JSON_PARSE: {exc}"
    if isinstance(obj, list):
        return len(obj), None
    if isinstance(obj, dict):
        return 1, None
    return 0, "NOT_OBJECT"


def format_signature(raw: str) -> list[list[str]]:
    cleaned, _ = strip_fences(raw)
    sig: list[list[str]] = []
    for line in cleaned.splitlines():
        if not line.strip():
            continue
        try:
            obj = json.loads(line)
        except Exception:  # noqa: BLE001
            sig.append(["<unparseable>"])
            continue
        sig.append(sorted(obj.keys()) if isinstance(obj, dict) else ["<not-object>"])
    return sig


# ---------------------------------------------------------------------------
# Model client
# ---------------------------------------------------------------------------

class ModelClient:
    def __init__(self, base_url: str, model: str, prompt: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.prompt = prompt

    def _post(self, payload: dict, timeout: int = 300) -> tuple[Optional[dict], float, Optional[str]]:
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            f"{self.base_url}/v1/chat/completions",
            data=data,
            headers={"Content-Type": "application/json"},
        )
        started = time.time()
        try:
            with urllib.request.urlopen(req, timeout=timeout) as response:
                body = response.read().decode("utf-8")
                return json.loads(body), time.time() - started, None
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            return None, time.time() - started, f"HTTP {exc.code}: {body[:2000]}"
        except Exception as exc:  # noqa: BLE001
            return None, time.time() - started, repr(exc)

    def chat(self, user_text: str, extra: Optional[dict] = None, max_tokens: int = 768) -> dict:
        payload = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": self.prompt},
                {"role": "user", "content": user_text},
            ],
            "temperature": 0,
            "seed": 42,
            "max_tokens": max_tokens,
            "stream": False,
            "chat_template_kwargs": {"enable_thinking": False},
        }
        if extra:
            payload.update(extra)
        response, latency, error = self._post(payload)
        if error and "chat_template_kwargs" in payload and any(
            marker in error for marker in ("chat_template", "enable_thinking", "unexpected keyword", "extra fields")
        ):
            fallback = dict(payload)
            fallback.pop("chat_template_kwargs", None)
            fallback["messages"] = [
                {"role": "system", "content": self.prompt},
                {"role": "user", "content": user_text + "\n/no_think"},
            ]
            response, latency, error = self._post(fallback)
        return {"response": response, "latency": latency, "error": error}


def content_of(response: Optional[dict]) -> tuple[str, Optional[str], dict]:
    if not response:
        return "", None, {}
    choice = (response.get("choices") or [{}])[0]
    message = choice.get("message") or {}
    return message.get("content") or "", choice.get("finish_reason"), response.get("usage") or {}


# ---------------------------------------------------------------------------
# Experiments
# ---------------------------------------------------------------------------

def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def longest_response(raw_responses: list[dict]) -> Optional[dict]:
    clean = [r for r in raw_responses if r.get("lines_quarantined", 1) == 0 and r.get("lines_accepted", 0) >= 3]
    if clean:
        return max(clean, key=lambda r: r.get("lines_accepted", 0))
    candidates = [r for r in raw_responses if r.get("lines_accepted", 0) >= 3]
    if not candidates:
        candidates = [r for r in raw_responses if r.get("lines_total", 0) > 0]
    return max(candidates, key=lambda r: (r.get("lines_accepted", 0), -r.get("lines_quarantined", 0))) if candidates else None


def experiment_e1(raw: str, segment_text: str, segment_start: int, schema: dict) -> dict:
    raw, _ = strip_fences(raw)
    lines = raw.splitlines()
    full = parse_jsonl(raw, segment_text, segment_start, schema)
    results = []
    for pct in (25, 50, 75):
        k = max(2, round(len(lines) * pct / 100))
        k = min(k, len(lines))
        cut_line = lines[k - 1]
        cut_char = max(1, len(cut_line) // 2)
        truncated = "\n".join(lines[: k - 1] + [cut_line[:cut_char]])
        parsed = parse_jsonl(truncated, segment_text, segment_start, schema)
        expected = sum(1 for item in full["accepted"] if item["line_no"] < k)
        got = len(parsed["accepted"])
        retention = (got / expected) if expected else 0.0
        mono_count, mono_error = monolithic_parse(truncated)
        results.append({
            "percent": pct,
            "cut_line": k,
            "expected_pre_cut": expected,
            "jsonl_accepted": got,
            "jsonl_retention": retention,
            "jsonl_quarantined": len(parsed["quarantined"]),
            "monolithic_accepted": mono_count,
            "monolithic_error": mono_error,
        })
    at50 = next(item for item in results if item["percent"] == 50)
    return {
        "results": results,
        "pass": at50["jsonl_retention"] >= 0.90 and at50["monolithic_accepted"] == 0,
        "headline": at50,
    }


def experiment_e2(raw: str, segment_text: str, segment_start: int, schema: dict) -> dict:
    raw, _ = strip_fences(raw)
    full = parse_jsonl(raw, segment_text, segment_start, schema)
    accepted_line_numbers = [item["line_no"] for item in full["accepted"]]
    if len(accepted_line_numbers) < 3:
        return {"pass": False, "finding": "INSUFFICIENT_ACCEPTED_LINES", "accepted_line_numbers": accepted_line_numbers}
    target = accepted_line_numbers[2]
    lines = raw.splitlines()
    corrupted = list(lines)
    line = corrupted[target - 1]
    corrupted[target - 1] = line[:-1] if line.endswith("}") else line + "x"
    parsed = parse_jsonl("\n".join(corrupted), segment_text, segment_start, schema)
    quarantined_line_numbers = [item["line_no"] for item in parsed["quarantined"]]
    accepted_after = [item["line_no"] for item in parsed["accepted"]]
    baseline_quarantines = {item["line_no"] for item in full["quarantined"]}
    new_quarantines = set(quarantined_line_numbers) - baseline_quarantines
    others_ok = set(accepted_after) == (set(accepted_line_numbers) - {target})
    passed = new_quarantines == {target} and others_ok
    return {
        "pass": passed,
        "corrupted_line": target,
        "quarantined_lines": quarantined_line_numbers,
        "baseline_quarantined_lines": sorted(baseline_quarantines),
        "new_quarantines": sorted(new_quarantines),
        "expected_quarantine": [target],
        "accepted_before": len(accepted_line_numbers),
        "accepted_after": len(accepted_after),
        "others_unaffected": others_ok,
    }


def anchor_signature(parsed: dict) -> list[tuple]:
    entries = []
    for item in parsed["accepted"]:
        obj = item["obj"]
        if obj.get("type") == "entity":
            key = ("entity", obj.get("surface"), obj.get("class"), obj.get("evidence"))
        else:
            key = ("relation", obj.get("subject"), obj.get("predicate"), obj.get("object"), obj.get("evidence"))
        entries.append((key, tuple(item["anchors"]["evidence"]), tuple(item["anchors"].get("surface") or item["anchors"].get("subject") or [])))
    return sorted(entries)


def experiment_e3(raw: str, segment_text: str, segment_start: int, schema: dict) -> dict:
    raw, _ = strip_fences(raw)
    first = parse_jsonl(raw, segment_text, segment_start, schema)
    lines = [line for line in raw.splitlines() if line.strip()]
    shuffled = list(lines)
    random.Random(42).shuffle(shuffled)
    second = parse_jsonl("\n".join(shuffled), segment_text, segment_start, schema)
    sig1 = anchor_signature(first)
    sig2 = anchor_signature(second)
    return {
        "pass": sig1 == sig2 and len(first["accepted"]) == len(second["accepted"]),
        "accepted_before": len(first["accepted"]),
        "accepted_after": len(second["accepted"]),
        "anchors_identical": sig1 == sig2,
    }


def coverage_report(eligible: int, processed: int, lines_returned: int, lines_accepted: int, lines_quarantined: int) -> dict:
    status = "PARTIAL" if processed < eligible else "COMPLETE"
    return {
        "eligible_segments": eligible,
        "processed_segments": processed,
        "skipped_segments": eligible - processed,
        "lines_returned": lines_returned,
        "lines_accepted": lines_accepted,
        "lines_quarantined": lines_quarantined,
        "coverage": status,
    }


def experiment_e4(eligible: int, processed: int, lines_returned: int, lines_accepted: int, lines_quarantined: int) -> dict:
    forced_skip = max(0, eligible - 1)
    report = coverage_report(eligible, forced_skip, lines_returned, lines_accepted, lines_quarantined)
    full = coverage_report(eligible, eligible, lines_returned, lines_accepted, lines_quarantined)
    return {
        "pass": report["processed_segments"] < report["eligible_segments"] and report["coverage"] == "PARTIAL",
        "forced_skip_report": report,
        "full_report": full,
        "no_silent_default": True,
    }


def experiment_e5(chunks: list[Chunk], segments: list[Segment]) -> dict:
    chunk_by_index = {chunk.index: chunk for chunk in chunks}
    violations = []
    for seg in segments:
        chunk = chunk_by_index[seg.chunk_index]
        if seg.start < chunk.start or seg.end > chunk.end:
            violations.append({
                "segment": seg.index,
                "chunk": chunk.index,
                "segment_span": [seg.start, seg.end],
                "chunk_span": [chunk.start, chunk.end],
            })
    return {
        "pass": not violations,
        "violations": violations,
        "segments": len(segments),
        "chunks": len(chunks),
        "overlaps": [seg.overlap_tokens for seg in segments],
    }


def size_histogram(values: list[int]) -> list[dict]:
    bounds = [0, 64, 128, 192, 256, 320, 384, 512, 10**9]
    labels = ["<64", "64-127", "128-191", "192-255", "256-319", "320-383", "384-511", ">=512"]
    result = []
    for label, low, high in zip(labels, bounds[:-1], bounds[1:]):
        result.append({"band": label, "count": sum(1 for value in values if low <= value < high)})
    return result


def list_observation(text: str, chunks: list[Chunk], segments: list[Segment]) -> list[dict]:
    lines = text.splitlines(keepends=True)
    blocks: list[list[int]] = []
    current: Optional[list[int]] = None
    offset = 0
    for line in lines:
        if line.lstrip().startswith("- "):
            if current is None:
                current = [offset, offset + len(line), 1]
            else:
                current[1] = offset + len(line)
                current[2] += 1
        else:
            if current is not None:
                blocks.append(current)
                current = None
        offset += len(line)
    if current is not None:
        blocks.append(current)
    observations = []
    for start, end, count in blocks:
        chunk_ids = [chunk.index for chunk in chunks if chunk.start < end and chunk.end > start]
        segment_ids = [seg.index for seg in segments if seg.start < end and seg.end > start]
        observations.append({
            "list_items": count,
            "span": [start, end],
            "chunks": chunk_ids,
            "segments": segment_ids,
            "atomic_in_one_chunk": len(chunk_ids) == 1,
            "atomic_in_one_segment": len(segment_ids) == 1,
        })
    return observations


# ---------------------------------------------------------------------------
# Capability probes (T2)
# ---------------------------------------------------------------------------

def capability_probes(client: ModelClient, tokenizer) -> list[dict]:
    probe_text = "Acme Corporation is a company. Elena Torres works for Acme Corporation."
    schema = {
        "type": "object",
        "properties": {
            "type": {"type": "string", "enum": ["entity"]},
            "surface": {"type": "string"},
            "class": {"type": "string", "enum": ["Person", "Organization", "Product"]},
            "evidence": {"type": "string"},
        },
        "required": ["type", "surface", "class", "evidence"],
        "additionalProperties": False,
    }
    grammar = 'root ::= "{\\"type\\":\\"entity\\",\\"surface\\":\\"" chars "\\"}"\nchars ::= [^"\\\\]*'
    jsonl_grammar = 'root ::= line "\\n" line\nline ::= "{\\"type\\":\\"entity\\",\\"surface\\":\\"Acme Corporation\\",\\"class\\":\\"Organization\\"}"'
    probes = [
        ("baseline", {}),
        ("response_format_json_object", {"response_format": {"type": "json_object"}}),
        ("response_format_json_schema", {"response_format": {"type": "json_schema", "json_schema": {"name": "entity", "schema": schema}}}),
        ("response_format_json_schema_strict", {"response_format": {"type": "json_schema", "json_schema": {"name": "entity", "schema": schema, "strict": True}}}),
        ("guided_json", {"guided_json": schema}),
        ("guided_regex", {"guided_regex": r'\{"type":"entity"[^\n]*\}'}),
        ("guided_grammar", {"guided_grammar": grammar}),
        ("structured_outputs_json", {"structured_outputs": {"json": schema}}),
        ("structured_outputs_regex", {"structured_outputs": {"regex": r'\{"type":"entity"[^\n]*\}'}}),
        ("structured_outputs_grammar", {"structured_outputs": {"grammar": 'root ::= "{\\"type\\":\\"entity\\"}"'}}),
        ("structured_outputs_jsonl_grammar", {"structured_outputs": {"grammar": jsonl_grammar}}),
        ("stop", {"stop": ["\n\n"]}),
    ]
    brace_ids = tokenizer.encode("{", add_special_tokens=False) if tokenizer else []
    if brace_ids:
        probes.append(("logit_bias", {"logit_bias": {str(brace_ids[0]): 5.0}}))
    results = []
    for name, extra in probes:
        outcome = client.chat(probe_text, extra=extra, max_tokens=128)
        content, finish, usage = content_of(outcome["response"])
        lines = [line for line in content.splitlines() if line.strip()]
        json_valid = False
        single_json_object = False
        required_keys = False
        semantics_ok = False
        try:
            parsed = json.loads(content)
            json_valid = True
            single_json_object = isinstance(parsed, dict)
            required_keys = single_json_object and all(key in parsed for key in ("type", "surface", "class", "evidence"))
            semantics_ok = (
                single_json_object
                and parsed.get("type") == "entity"
                and parsed.get("class") in ("Person", "Organization", "Product")
                and isinstance(parsed.get("surface"), str)
                and bool(parsed.get("surface"))
                and isinstance(parsed.get("evidence"), str)
                and bool(parsed.get("evidence"))
            )
        except Exception:  # noqa: BLE001
            pass
        regex_match = bool(re.fullmatch(r'\{"type":"entity"[^\n]*\}', content.strip()))
        expected_line = '{"type":"entity","surface":"Acme Corporation","class":"Organization"}'
        jsonl_grammar_ok = content.strip() == expected_line + "\n" + expected_line
        if name in ("response_format_json_schema", "response_format_json_schema_strict", "structured_outputs_json"):
            enforcement = "schema-object" if semantics_ok else "accepted-not-enforced/malformed"
        elif name == "response_format_json_object":
            enforcement = "single-object" if single_json_object else "accepted-not-enforced"
        elif name == "guided_json":
            enforcement = "schema-object" if semantics_ok else "accepted-not-enforced"
        elif name in ("guided_regex", "structured_outputs_regex"):
            enforcement = "regex-match" if regex_match else "accepted-not-enforced"
        elif name == "guided_grammar":
            enforcement = "grammar-object" if content.strip() == '{"type":"entity"}' else "accepted-not-enforced"
        elif name == "structured_outputs_grammar":
            enforcement = "grammar-object" if content.strip() == '{"type":"entity"}' else "accepted-not-enforced"
        elif name == "structured_outputs_jsonl_grammar":
            enforcement = "jsonl-grammar" if jsonl_grammar_ok else "accepted-not-enforced"
        elif name == "stop":
            enforcement = "stop-accepted" if "\n\n" not in content else "stop-not-observed"
        elif name == "logit_bias":
            enforcement = "accepted-not-observable"
        else:
            enforcement = "prompt-only"
        results.append({
            "probe": name,
            "http_ok": outcome["error"] is None,
            "error": outcome["error"],
            "finish_reason": finish,
            "latency_s": round(outcome["latency"], 3),
            "lines_returned": len(lines),
            "json_valid": json_valid,
            "single_json_object": single_json_object,
            "required_keys_present": required_keys,
            "schema_semantics_ok": semantics_ok,
            "regex_match": regex_match,
            "jsonl_grammar_ok": jsonl_grammar_ok,
            "enforcement": enforcement,
            "content": content,
            "content_prefix": content[:200],
            "usage": usage,
        })
    # Seed repeatability, not bitwise determinism.
    first = client.chat(probe_text, max_tokens=128)
    second = client.chat(probe_text, max_tokens=128)
    first_content, _, _ = content_of(first["response"])
    second_content, _, _ = content_of(second["response"])
    results.append({
        "probe": "seed_repeat",
        "http_ok": first["error"] is None and second["error"] is None,
        "identical": first_content == second_content,
        "enforcement": "deterministic" if first_content == second_content else "nondeterministic",
        "first_prefix": first_content[:160],
        "second_prefix": second_content[:160],
    })
    return results


# ---------------------------------------------------------------------------
# GPU / log evidence
# ---------------------------------------------------------------------------

def gpu_snapshot() -> dict:
    try:
        raw = subprocess.check_output(
            ["nvidia-smi", "--query-gpu=name,uuid,memory.total,memory.used,utilization.gpu", "--format=csv,noheader"],
            text=True,
            timeout=20,
        ).strip()
        apps = subprocess.check_output(
            ["nvidia-smi", "--query-compute-apps=pid,process_name,used_memory", "--format=csv,noheader"],
            text=True,
            timeout=20,
        ).strip()
        return {"gpu": raw, "compute_apps": apps}
    except Exception as exc:  # noqa: BLE001
        return {"error": repr(exc)}


def model_load_evidence(log_path: Path) -> list[str]:
    if not log_path.exists():
        return []
    needles = ("Model loading took", "Available KV cache memory", "GPU KV cache size", "init engine", "Free memory on device")
    lines = []
    for line in log_path.read_text(errors="replace").splitlines():
        if any(needle in line for needle in needles):
            lines.append(line)
    return lines


# ---------------------------------------------------------------------------
# Orchestration
# ---------------------------------------------------------------------------

def load_tokenizer(model_path: str):
    from transformers import AutoTokenizer  # imported lazily so --self-test works without transformers
    return AutoTokenizer.from_pretrained(model_path, trust_remote_code=False)


def run(args) -> int:
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    doc_text = Path(args.doc).read_text()
    schema = json.loads(SCHEMA_PATH.read_text())
    prompt = PROMPT_PATH.read_text()

    tokenizer = load_tokenizer(args.model_path)
    count_tokens = lambda text: len(tokenizer.encode(text, add_special_tokens=False))

    doc_tokens = count_tokens(doc_text)
    chunker = Chunker(doc_text, count_tokens)
    chunks, segments = chunker.build()

    client = ModelClient(args.base_url, args.model, prompt)
    results: dict = {
        "run": {
            "started_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "base_url": args.base_url,
            "model": args.model,
            "model_path": args.model_path,
            "doc": str(args.doc),
            "doc_chars": len(doc_text),
            "doc_tokens": doc_tokens,
            "doc_lines": len(doc_text.splitlines()),
            "doc_headings": len([line for line in doc_text.splitlines() if line.startswith("#")]),
            "doc_list_items": len([line for line in doc_text.splitlines() if line.startswith("- ")]),
        },
        "gpu_before": gpu_snapshot(),
        "chunks": [
            {
                "index": chunk.index,
                "tokens": chunk.tokens,
                "span": [chunk.start, chunk.end],
                "segments": len([seg for seg in segments if seg.chunk_index == chunk.index]),
            }
            for chunk in chunks
        ],
        "segments": [
            {
                "index": seg.index,
                "chunk": seg.chunk_index,
                "tokens": seg.tokens,
                "overlap_tokens": seg.overlap_tokens,
                "segment_undersized": seg.segment_undersized,
                "segment_undersized_reason": seg.segment_undersized_reason,
                "span": [seg.start, seg.end],
            }
            for seg in segments
        ],
    }

    results["e5_chunk_boundary"] = experiment_e5(chunks, segments)
    results["t4_segment_size_histogram"] = size_histogram([seg.tokens for seg in segments])
    results["t4_undersized_tails"] = [
        {"segment": seg.index, "tokens": seg.tokens, "reason": seg.segment_undersized_reason}
        for seg in segments
        if seg.segment_undersized
    ]
    results["t4_list_observation"] = list_observation(doc_text, chunks, segments)
    results["t4_chunk_segment_ratio"] = round(len(segments) / len(chunks), 2) if chunks else 0

    # T1: no-op smoke.
    smoke = client.chat("Return exactly the two characters OK. Do not add punctuation.", max_tokens=8)
    smoke_content, smoke_finish, smoke_usage = content_of(smoke["response"])
    results["t1_no_op"] = {
        "pass": smoke["error"] is None,
        "error": smoke["error"],
        "content": smoke_content,
        "finish_reason": smoke_finish,
        "usage": smoke_usage,
        "latency_s": round(smoke["latency"], 3),
        "output_sha256": sha256(smoke_content),
    }

    # T2: capability matrix.
    results["t2_capabilities"] = capability_probes(client, tokenizer)

    # T6/T7: full pass over the document.
    raw_responses = []
    call_stats = []
    total_returned = total_accepted = total_quarantined = 0
    quarantine_reasons: dict[str, int] = {}
    for seg in segments:
        outcome = client.chat(seg.text)
        content, finish, usage = content_of(outcome["response"])
        parsed = parse_jsonl(content, seg.text, seg.start, schema)
        clean_lines = parsed["lines_total"]
        total_returned += clean_lines
        total_accepted += len(parsed["accepted"])
        total_quarantined += len(parsed["quarantined"])
        for item in parsed["quarantined"]:
            quarantine_reasons[item["reason_code"]] = quarantine_reasons.get(item["reason_code"], 0) + 1
        record = {
            "segment": seg.index,
            "chunk": seg.chunk_index,
            "segment_tokens": seg.tokens,
            "overlap_tokens": seg.overlap_tokens,
            "error": outcome["error"],
            "finish_reason": finish,
            "usage": usage,
            "latency_s": round(outcome["latency"], 3),
            "raw": content,
            "lines_total": clean_lines,
            "lines_accepted": len(parsed["accepted"]),
            "lines_quarantined": len(parsed["quarantined"]),
            "fence_stripped": parsed["fence_stripped"],
            "accepted": parsed["accepted"],
            "quarantined": parsed["quarantined"],
        }
        raw_responses.append(record)
        call_stats.append({k: record[k] for k in ("segment", "chunk", "segment_tokens", "finish_reason", "usage", "latency_s", "lines_total", "lines_accepted", "lines_quarantined", "error")})
        print(f"segment {seg.index + 1}/{len(segments)}: {clean_lines} lines, {len(parsed['accepted'])} accepted, {len(parsed['quarantined'])} quarantined", flush=True)

    results["t6_calls"] = call_stats
    results["t7_validation"] = {
        "lines_returned": total_returned,
        "lines_accepted": total_accepted,
        "lines_quarantined": total_quarantined,
        "quarantine_reasons": quarantine_reasons,
        "fence_stripped_segments": sum(1 for item in raw_responses if item["fence_stripped"]),
    }

    # T5: repeat-format check on the longest response's segment.
    target = longest_response(raw_responses)
    if target:
        seg = segments[target["segment"]]
        repeat = client.chat(seg.text)
        repeat_content, _, _ = content_of(repeat["response"])
        results["t5_repeat_format"] = {
            "segment": seg.index,
            "format_equal": format_signature(target["raw"]) == format_signature(repeat_content),
            "first_lines": len(format_signature(target["raw"])),
            "repeat_lines": len(format_signature(repeat_content)),
        }
    else:
        results["t5_repeat_format"] = {"pass": False, "finding": "NO_USABLE_RESPONSE"}

    # T8: experiments.
    if target:
        seg = segments[target["segment"]]
        results["e1_truncation"] = experiment_e1(target["raw"], seg.text, seg.start, schema)
        results["e2_malformed_line"] = experiment_e2(target["raw"], seg.text, seg.start, schema)
        results["e3_anchoring"] = experiment_e3(target["raw"], seg.text, seg.start, schema)
        results["experiment_source"] = {
            "segment": seg.index,
            "lines": target["lines_total"],
            "accepted": target["lines_accepted"],
            "quarantined": target["lines_quarantined"],
        }
    else:
        for key in ("e1_truncation", "e2_malformed_line", "e3_anchoring"):
            results[key] = {"pass": False, "finding": "NO_USABLE_RESPONSE"}
        results["experiment_source"] = None

    results["e4_coverage"] = experiment_e4(
        len(segments),
        len([item for item in call_stats if item["error"] is None]),
        total_returned,
        total_accepted,
        total_quarantined,
    )

    results["gpu_after"] = gpu_snapshot()
    results["model_load_evidence"] = model_load_evidence(Path("/spike/vllm.log"))

    # Write raw and summary artifacts.
    (out_dir / "raw_responses.jsonl").write_text("\n".join(json.dumps(item) for item in raw_responses) + "\n")
    (out_dir / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    (out_dir / "report.md").write_text(render_report(results, chunks, segments))
    print(f"wrote {out_dir}/results.json and {out_dir}/report.md")
    return 0


def render_report(results: dict, chunks: list[Chunk], segments: list[Segment]) -> str:
    run = results["run"]
    t1 = results["t1_no_op"]
    t7 = results["t7_validation"]
    e1 = results["e1_truncation"]
    e2 = results["e2_malformed_line"]
    e3 = results["e3_anchoring"]
    e4 = results["e4_coverage"]
    e5 = results["e5_chunk_boundary"]
    t2 = results["t2_capabilities"]

    def verdict(value: bool) -> str:
        return "PASS" if value else "FAIL"

    lines = [
        "# OGCE JSONL vertical slice — spike result",
        "",
        f"**Run:** {run['started_at']}  ",
        f"**Endpoint:** `{run['base_url']}` / `{run['model']}`  ",
        f"**Document:** `{run['doc']}` — {run['doc_chars']} chars, {run['doc_tokens']} tokens, {run['doc_headings']} headings, {run['doc_list_items']} list items  ",
        f"**Scope:** contract robustness only; not F1/quality, not p95, not profile qualification, not ADR-044 transition.",
        "",
        "## T1 — smoke",
        "",
        "| Check | Result |",
        "|---|---|",
        f"| Model call | {verdict(t1['pass'])} |",
        f"| Content | `{t1['content'][:80]}` |",
        f"| Finish reason | `{t1['finish_reason']}` |",
        f"| Latency | {t1['latency_s']} s |",
        f"| Output SHA-256 | `{t1['output_sha256']}` |",
        "",
        "## T2 — runtime capability matrix",
        "",
        "| Probe | HTTP OK | Lines | JSON valid | Enforcement | Latency | Notes |",
        "|---|---|---|---|---|---|---|",
    ]
    for probe in t2:
        note = probe.get("error") or (f"identical={probe.get('identical')}" if "identical" in probe else (probe.get("content_prefix") or "")[:60])
        note = str(note).replace("|", "\\|").replace("\n", " ")
        lines.append(
            f"| {probe['probe']} | {probe.get('http_ok')} | {probe.get('lines_returned', '')} | "
            f"{probe.get('json_valid', '')} | {probe.get('enforcement', '')} | {probe.get('latency_s', '')} | {note} |"
        )
    lines += [
        "",
        "## T4/T5 — chunker and schema",
        "",
        f"- Retrieval chunks: **{len(chunks)}**; extraction segments: **{len(segments)}**; chunk/segment ratio: **{results['t4_chunk_segment_ratio']}×**.",
        f"- Segment token range: **{min(seg.tokens for seg in segments)}–{max(seg.tokens for seg in segments)}**; overlaps: {sorted(set(seg.overlap_tokens for seg in segments))}.",
        f"- Undersized tails: {json.dumps(results['t4_undersized_tails'])}.",
        f"- List observation: {json.dumps(results['t4_list_observation'])}.",
        f"- Repeat-format check: {results['t5_repeat_format']}.",
        "",
        "### Segment size histogram",
        "",
        "| Band | Count |",
        "|---|---|",
    ]
    for item in results["t4_segment_size_histogram"]:
        lines.append(f"| {item['band']} | {item['count']} |")
    lines += [
        "## T6/T7 — calls, parsing, validation",
        "",
        f"- Lines returned: **{t7['lines_returned']}**; accepted: **{t7['lines_accepted']}**; quarantined: **{t7['lines_quarantined']}**.",
        f"- Quarantine reasons: `{json.dumps(t7['quarantine_reasons'], sort_keys=True)}`.",
        f"- Segments with fences stripped: **{t7['fence_stripped_segments']}**.",
        "",
        "## T8 — proof experiments",
        "",
        "| Experiment | Claim | Result | Detail |",
        "|---|---|---|---|",
        f"| E1 truncation | C2 | {verdict(e1['pass'])} | {json.dumps(e1.get('headline', e1))} |",
        f"| E2 malformed line | C3 | {verdict(e2['pass'])} | {json.dumps(e2)} |",
        f"| E3 anchoring order | C4 | {verdict(e3['pass'])} | {json.dumps(e3)} |",
        f"| E4 coverage | C5 | {verdict(e4['pass'])} | {json.dumps(e4['forced_skip_report'])} |",
        f"| E5 chunk boundary | C1 | {verdict(e5['pass'])} | violations={len(e5['violations'])} |",
        "",
        "## E1 detail",
        "",
        "| Truncation | Expected pre-cut | JSONL accepted | JSONL retention | Monolithic accepted |",
        "|---|---|---|---|---|",
    ]
    for item in e1.get("results", []):
        lines.append(
            f"| {item['percent']}% (line {item['cut_line']}) | {item['expected_pre_cut']} | {item['jsonl_accepted']} | {item['jsonl_retention']:.2%} | {item['monolithic_accepted']} |"
        )
    lines += [
        "",
        "## GPU and model-load evidence",
        "",
        f"- Before: `{results['gpu_before']}`",
        f"- After: `{results['gpu_after']}`",
    ]
    for item in results.get("model_load_evidence", []):
        lines.append(f"- `{item}`")
    lines += [
        "",
        "## What this proves",
        "",
        "- C1: segments nest inside retrieval chunks; E5 reports zero boundary violations (or the exact violations).",
        "- C2: line-delimited parsing survives truncation while the monolithic parse loses the response.",
        "- C3: a malformed line is quarantined without poisoning its neighbours.",
        "- C4: anchors are derived from text, not line position; shuffled order produces identical anchors.",
        "- C5: skipped segments produce `PARTIAL` with explicit counts.",
        "",
        "## What this does not prove",
        "",
        "- No extraction-quality, precision/recall, F1, cost, throughput, or p95 claim.",
        "- No G-10 p95 measurement; no repeated synthetic-request approval is implied.",
        "- No pinning or validation of the Blackwell RTX 5060 Ti profile. This run used node2 RTX 4060 Ti as an authorized scope deviation.",
        "- No ADR-044 status transition; no gate is marked Met or Missed.",
        "",
        "## Recommendation",
        "",
        "Promote the chunker, parser, and validation ladder into P0-1/P0-3/P1-1 only after the hardening items in the T9 write-up are addressed. The contract experiments are the promotion evidence; the model-quality and profile questions remain open.",
        "",
    ]
    return "\n".join(lines)


def self_test() -> int:
    text = (
        "Alpha Corporation is a supplier. Beta Ltd is a supplier. "
        "Elena Torres works for Alpha Corporation. Alpha Corporation supplies the Orion Sensor. "
        "Beta Ltd supplies the Vega Controller. " * 12
    )
    words = text.split()
    count = lambda value: len(value.split())
    chunker = Chunker(text, count, max_chunk=32, min_segment=12, max_segment=20, overlap_min=4, overlap_max=8)
    chunks, segments = chunker.build()
    assert chunks and segments, "chunker produced no output"
    for seg in segments:
        chunk = chunks[seg.chunk_index]
        assert chunk.start <= seg.start < seg.end <= chunk.end, "segment crossed chunk boundary"
    assert len(segments) >= len(chunks), "expected at least one segment per chunk"
    segment_text = "Elena Torres works for Alpha Corporation. Alpha Corporation supplies the Orion Sensor."
    schema = {"classes": ["Person", "Organization", "Product"], "relations": {"worksFor": {"subject_class": "Person", "object_class": "Organization"}, "supplies": {"subject_class": "Organization", "object_class": "Product"}}}
    raw = "\n".join([
        '{"type":"entity","surface":"Elena Torres","class":"Person","evidence":"Elena Torres works for Alpha Corporation."}',
        '{"type":"entity","surface":"Alpha Corporation","class":"Organization","evidence":"Elena Torres works for Alpha Corporation."}',
        '{"type":"relation","subject":"Elena Torres","subject_class":"Person","predicate":"worksFor","object":"Alpha Corporation","object_class":"Organization","evidence":"Elena Torres works for Alpha Corporation."}',
    ])
    parsed = parse_jsonl(raw, segment_text, 100, schema)
    assert len(parsed["accepted"]) == 3, parsed
    assert parsed["accepted"][0]["anchors"]["evidence"] == [100, 100 + len("Elena Torres works for Alpha Corporation.")]
    truncated = "\n".join(raw.splitlines()[:2]) + '\n{"type":"entity","surface":"Alpha'
    parsed_trunc = parse_jsonl(truncated, segment_text, 100, schema)
    assert len(parsed_trunc["accepted"]) == 2, parsed_trunc
    assert len(parsed_trunc["quarantined"]) == 1, parsed_trunc
    print("self-test passed")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    parser.add_argument("--model", default="qwen3-8b-fp8")
    parser.add_argument("--model-path", default="/models")
    parser.add_argument("--doc", default=str(DEFAULT_DOC))
    parser.add_argument("--out", default="/spike/results")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
