"""Offline two-stage extraction helpers; no provider calls."""
from __future__ import annotations
import hashlib, math, re
from offline_validation import norm_predicate, norm_surface, validate_entities, validate_relations

ALLOWED_TYPES = frozenset("MaintenanceSystem, Component, Protocol, Procedure, Material, Organization, Person, Role, Product, Document, Standard, Metric, Measurement, Location, Time, Algorithm, DataStructure, Concept, Event, Problem".split(", "))
ALLOWED_PREDICATES = frozenset("has_component, part_of, tested_every, replaced_every, signs, records, documents, performed_on, logs, verifies, replaced_at, requires, repeats_every, applied_to, is_a, used_in, improves_over, discussed_in, same_as, seeks, visits, combines, implements, uses, has, related_to, computes, measures, obeys, matches".split(", "))

def plan_chunks(text, target_chars=5000, overlap_chars=200, max_output_tokens=32768,
                output_char_ratio=3.0, min_output_tokens=2048):
    """Return deterministic chunks with coverage and an output budget.

    The default 32K completion ceiling is intended for providers that expose it.
    JSON output can be several times the input character count, so the budget is
    derived from input size and capped by the provider capability.
    """
    if target_chars <= 0: raise ValueError("target_chars must be positive")
    if overlap_chars < 0 or overlap_chars >= target_chars: raise ValueError("invalid overlap")
    if output_char_ratio <= 0: raise ValueError("invalid output_char_ratio")
    step = target_chars - overlap_chars
    chunks = []
    for cid, start in enumerate(range(0, len(text), step)):
        end = min(len(text), start + target_chars)
        body = text[start:end]
        estimated_output_tokens = math.ceil(len(body) * output_char_ratio / 4)
        chunks.append({
            "chunk_id": cid, "char_start": start, "char_end": end,
            "chars": len(body), "sha256": hashlib.sha256(body.encode()).hexdigest(),
            "estimated_input_tokens": math.ceil(len(body) / 4),
            "output_char_ratio": output_char_ratio,
            "recommended_max_tokens": min(max_output_tokens, max(min_output_tokens, estimated_output_tokens)),
        })
        if end == len(text): break
    return chunks

def validate_provider_output_budget(required_tokens, provider_max_output_tokens):
    """Fail closed when a provider cannot support the planned output budget."""
    if not isinstance(provider_max_output_tokens, int) or provider_max_output_tokens <= 0:
        return "provider max output limit is missing or invalid"
    if provider_max_output_tokens < required_tokens:
        return f"provider max output {provider_max_output_tokens} < required {required_tokens}"
    return None

def preflight_output_budget(chunks, provider_max_output_tokens, provider_limit_source):
    """Pre-inference gate: verify the advertised runtime limit, then every chunk."""
    errors = []
    if not provider_limit_source:
        errors.append({"reason": "provider output limit not verified at runtime"})
    if not isinstance(provider_max_output_tokens, int) or provider_max_output_tokens <= 0:
        errors.append({"reason": "provider output limit missing or invalid"})
        return errors
    for chunk in chunks:
        required = chunk["recommended_max_tokens"]
        if required > provider_max_output_tokens:
            errors.append({
                "reason": "chunk output budget exceeds provider limit",
                "chunk_id": chunk.get("chunk_id"),
                "required_tokens": required,
                "provider_max_tokens": provider_max_output_tokens,
            })
    return errors

def stage1_entities(text, candidates, allowed_types=ALLOWED_TYPES):
    accepted = []
    for e in candidates:
        errs = validate_entities(text, [e], allowed_types)
        if not errs:
            accepted.append(dict(e))
    return accepted

def stage2_relations(text, entities, candidates, allowed_predicates=ALLOWED_PREDICATES):
    accepted, rejected = [], []
    ids = {e["mention_id"] for e in entities}
    for r in candidates:
        direction = r.get("direction", "subject_to_object")
        if direction not in ("subject_to_object", "object_to_subject"):
            rejected.append((r, "invalid_direction")); continue
        s, o = r.get("subject_id"), r.get("object_id")
        if s not in ids or o not in ids:
            rejected.append((r, "missing_endpoint")); continue
        if direction == "object_to_subject": s, o = o, s
        if s == o:
            rejected.append((r, "self_relation")); continue
        pred = norm_predicate(r.get("predicate"))
        if pred not in allowed_predicates:
            rejected.append((r, "predicate_not_allowed")); continue
        es, ee = r.get("evidence_span_start"), r.get("evidence_span_end")
        if not isinstance(es, int) or not isinstance(ee, int) or es < 0 or ee <= es or ee > len(text):
            rejected.append((r, "invalid_evidence")); continue
        accepted.append({"subject_id": s, "predicate": pred, "object_id": o,
                         "direction": "subject_to_object", "evidence_span_start": es,
                         "evidence_span_end": ee})
    return accepted, rejected

def merge_chunk_entities(chunks):
    """chunks = [(chunk_meta, [entities])]; shift spans and preserve provenance."""
    merged, seen = [], set()
    for meta, entities in chunks:
        for e in entities:
            start = meta["char_start"] + int(e["char_start"])
            end = meta["char_start"] + int(e["char_end"])
            if (start, end) in seen: continue
            seen.add((start, end))
            merged.append({**e, "mention_id": f"m{len(merged)+1}",
                           "char_start": start, "char_end": end,
                           "chunk_id": meta["chunk_id"],
                           "chunk_char_start": meta["char_start"]})
    return merged
