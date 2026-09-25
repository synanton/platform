"""Run validity: a benchmark row is reported only if it measured what its label says.

The old ``results/T03.yaml`` ("hybrid", every metric 0.0) never ran dense retrieval,
and nothing flagged it. These checks make that impossible for new runs
(docs/research/retrieval-evaluation-benchmark-plan.md §6 Phase B1-G, G4).
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .query import IndexStats


@dataclass
class Validity:
    reasons: list[str] = field(default_factory=list)

    @property
    def valid(self) -> bool:
        return not self.reasons

    def fail(self, reason: str) -> None:
        self.reasons.append(reason)

    def as_dict(self) -> dict:
        return {"valid": self.valid, "reasons": list(self.reasons)}


def dense_expected(embedding_model: str, top_k_dense: int | None) -> bool:
    """A run uses dense retrieval unless it names no model or suppresses the dense side."""
    return embedding_model.lower() not in ("", "none") and top_k_dense != 0


def check_run(
    *,
    dense: bool,
    embed_skipped_queries: list[str],
    failed_queries: list[str],
    stats: IndexStats | None,
    embedding_model: str,
    embedding_dim: int | None,
    spend_before: float | None,
    spend_after: float | None,
    free_only: bool,
    aborted: str | None,
) -> Validity:
    v = Validity()
    if aborted:
        v.fail(f"run aborted before completion: {aborted}")
    if failed_queries:
        v.fail(f"{len(failed_queries)} queries failed: {', '.join(failed_queries)}")
    if dense:
        if embed_skipped_queries:
            v.fail(f"embedding skipped (BM25-only) for {len(embed_skipped_queries)} queries: "
                   f"{', '.join(embed_skipped_queries)}")
        if stats is None:
            v.fail("no /index/stats available")
        elif stats.fully_vectorised is None:
            v.fail("index has no coverage report (not built by this synquest process) — POST /reindex first")
        elif not stats.fully_vectorised:
            v.fail(f"index not fully vectorised: vector_docs={stats.vector_docs}/{stats.doc_count}, "
                   f"dim_mismatches={stats.dim_mismatches}, missing_vectors={stats.missing_vectors}")
        if stats is not None and stats.embedding_model is not None and stats.embedding_model != embedding_model:
            v.fail(f"index built with model '{stats.embedding_model}', run labelled '{embedding_model}'")
        if (stats is not None and stats.embedding_dim is not None and embedding_dim is not None
                and stats.embedding_dim != embedding_dim):
            v.fail(f"index dim {stats.embedding_dim} ≠ run embedding_dim {embedding_dim}")
    if free_only:
        if spend_before is None or spend_after is None:
            v.fail("free-models run without a spend check (--spend-cmd)")
        elif spend_after > spend_before + 1e-9:
            v.fail(f"provider spend increased: ${spend_before} → ${spend_after}")
    return v
