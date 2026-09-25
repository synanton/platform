"""Marker-based gold annotation (T-INT-3).

A query row may carry
    "gold_source": "<file name>",
    "gold_markers": ["<distinctive answer text>", ...]
Its gold_chunk_ids for a tenant are every chunk of that source file whose whitespace-normalised
text contains any marker. The gold stays objective and reproducible across tenants with
different chunking (fixed vs semantic), and nobody hand-picks chunk UUIDs per tenant. Rows
without markers keep their existing gold_chunk_ids, e.g. the original 10 queries, whose gold
was annotated by hand and then carried over with `remap-gold`.

Chunk text comes from Cassandra via `cqlsh COPY ... TO` CSV in the compose container
(read-only). COPY is used because `SELECT JSON` output wraps long text.
"""

from __future__ import annotations

import csv
import re
import subprocess
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

# (tenant) -> list of {"ref", "uri", "ordinal", "text"} for that tenant
ChunkExport = Callable[[str], list[dict]]


def normalise(text: str) -> str:
    return re.sub(r"\s+", " ", (text or "").replace("\\n", " ")).strip()


def docker_copy_export(container: str = "docker-cassandra-1") -> ChunkExport:
    def export(tenant: str) -> list[dict]:
        with tempfile.TemporaryDirectory() as tmp:
            for table, cols in (("manifest", "tenant_id, content_ref_id, source_uri"),
                                ("chunks_payload", "tenant_id, content_ref_id, chunk_ordinal, chunk_text")):
                cql = f"COPY ingestion_cache.{table} ({cols}) TO '/tmp/annotate-{table}.csv' WITH HEADER=true;"
                subprocess.run(["docker", "exec", container, "cqlsh", "-e", cql], check=True, capture_output=True, timeout=300)
                subprocess.run(["docker", "cp", f"{container}:/tmp/annotate-{table}.csv", f"{tmp}/{table}.csv"],
                               check=True, capture_output=True, timeout=120)
            csv.field_size_limit(10 ** 9)
            uris = {r["content_ref_id"]: r["source_uri"] for r in csv.DictReader(open(f"{tmp}/manifest.csv"))
                    if r["tenant_id"] == tenant}
            return [{"ref": r["content_ref_id"], "uri": uris.get(r["content_ref_id"], ""),
                     "ordinal": int(r["chunk_ordinal"]), "text": r["chunk_text"]}
                    for r in csv.DictReader(open(f"{tmp}/chunks_payload.csv")) if r["tenant_id"] == tenant]
    return export


@dataclass
class AnnotateReport:
    annotated: int = 0
    kept: int = 0
    unmatched: list[str] = field(default_factory=list)


def annotate(rows: list[dict], chunks: list[dict], tenant: str) -> tuple[list[dict], AnnotateReport]:
    report = AnnotateReport()
    out = []
    norm = [(c, normalise(c["text"])) for c in chunks]
    for row in rows:
        markers = row.get("gold_markers")
        if not markers:
            out.append(row)
            report.kept += 1
            continue
        source = row.get("gold_source", "")
        wanted = [normalise(m) for m in markers]
        ids = sorted({f"{c['ref']}#{c['ordinal']}" for c, t in norm
                      if (not source or c["uri"].endswith("/" + source)) and any(m in t for m in wanted)})
        new = dict(row)
        new["gold_chunk_ids"] = ids
        new["notes"] = f"gold by marker match in {source or 'any source'} for tenant={tenant}"
        out.append(new)
        report.annotated += 1
        if not ids:
            report.unmatched.append(row["query_id"])
    return out, report
