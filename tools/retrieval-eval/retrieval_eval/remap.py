"""Carry annotated gold chunk IDs from one tenant to another.

Gold IDs are ``<content_ref_id>#<chunk_ordinal>``, and ``content_ref_id`` differs per tenant
(plan §6 Phase B0 item 2). A GPU-plane tenant (e.g. ``rb-fixed-g``) re-ingests the same
corpus with the same chunker as its source tenant (``rb-fixed``). So each gold ID maps by
``(source_uri, chunk_ordinal)``, and the mapping is only accepted when the chunk text hash
(``chunk_sha256``) is identical in both tenants. A changed chunk is reported and needs
manual re-annotation (``retrieval-eval inspect``); it is never guessed.

Reads ``ingestion_cache`` in Cassandra through ``cqlsh`` in the compose ``cassandra``
container (read-only ``SELECT JSON`` queries).
"""

from __future__ import annotations

import json
import subprocess
from dataclasses import dataclass, field
from typing import Callable

CqlRunner = Callable[[str], list[dict]]


def docker_cqlsh(container: str = "docker-cassandra-1") -> CqlRunner:
    def run(cql: str) -> list[dict]:
        proc = subprocess.run(["docker", "exec", container, "cqlsh", "-e", cql],
                              capture_output=True, text=True, timeout=120)
        if proc.returncode != 0:
            raise RuntimeError(f"cqlsh failed: {proc.stderr.strip()[-300:]}")
        return [json.loads(line.strip()) for line in proc.stdout.splitlines() if line.strip().startswith("{")]
    return run


def _q(value: str) -> str:
    return value.replace("'", "''")


def manifest(cql: CqlRunner, tenant: str) -> dict[str, str]:
    """content_ref_id → source_uri for one tenant."""
    rows = cql(f"SELECT JSON content_ref_id, source_uri FROM ingestion_cache.manifest "
               f"WHERE tenant_id='{_q(tenant)}' ALLOW FILTERING;")
    return {r["content_ref_id"]: r["source_uri"] for r in rows}


def chunk_hashes(cql: CqlRunner, tenant: str, ref: str) -> dict[int, str]:
    rows = cql(f"SELECT JSON chunk_ordinal, chunk_sha256 FROM ingestion_cache.chunks_payload "
               f"WHERE tenant_id='{_q(tenant)}' AND content_ref_id={ref};")
    return {int(r["chunk_ordinal"]): r["chunk_sha256"] for r in rows}


@dataclass
class RemapReport:
    mapped: int = 0
    unmapped: list[str] = field(default_factory=list)  # "<query_id>: <old id> (<reason>)"


def remap_queries(rows: list[dict], cql: CqlRunner, from_tenant: str, to_tenant: str) -> tuple[list[dict], RemapReport]:
    src, dst = manifest(cql, from_tenant), manifest(cql, to_tenant)
    dst_by_uri = {uri: ref for ref, uri in dst.items()}
    hashes: dict[tuple[str, str], dict[int, str]] = {}

    def h(tenant, ref):
        if (tenant, ref) not in hashes:
            hashes[(tenant, ref)] = chunk_hashes(cql, tenant, ref)
        return hashes[(tenant, ref)]

    report = RemapReport()
    out = []
    for row in rows:
        new_ids = []
        for gid in row.get("gold_chunk_ids", []):
            ref, _, ordinal = gid.partition("#")
            uri = src.get(ref)
            reason = None
            if uri is None:
                reason = f"not in {from_tenant}"
            elif uri not in dst_by_uri:
                reason = f"{uri} not ingested in {to_tenant}"
            else:
                new_ref = dst_by_uri[uri]
                a, b = h(from_tenant, ref).get(int(ordinal)), h(to_tenant, new_ref).get(int(ordinal))
                if a is None or b is None or a != b:
                    reason = "chunk text differs (re-annotate)"
                else:
                    new_ids.append(f"{new_ref}#{ordinal}")
                    report.mapped += 1
            if reason:
                report.unmapped.append(f"{row['query_id']}: {gid} ({reason})")
        new_row = dict(row)
        new_row["gold_chunk_ids"] = new_ids
        new_row["notes"] = (f"remapped from tenant={from_tenant} to tenant={to_tenant} by (source_uri, "
                            f"chunk_ordinal) with identical chunk_sha256 — {row.get('notes', '')}").strip(" —")
        out.append(new_row)
    return out, report
