"""Composition execution (BR-A2.1): executor abstraction + simulated executor.

A CompositionExecutor runs one manifest composition against a corpus over a
query set and returns raw per-query results. The simulated executor produces
deterministic synthetic results from the manifest seed (stdlib PRNG only —
no wall-clock, no unseeded randomness); the Synquest-backed executor lands
with B5 adapter availability against the same ABC.
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field


@dataclass(frozen=True)
class RawQueryResult:
    query_id: str
    hits: list
    eligible_set: list
    timing_ms: float
    timing_scope: str
    topology: str
    structural_empty: bool = False


@dataclass(frozen=True)
class ExecutionContext:
    corpus_path: str
    seed: int
    event_counter_factory=None
    timeout_ms: int = None


class CompositionExecutor:
    """Runs one composition. Implementations: simulated (A2) and Synquest-backed (B5)."""

    def execute(self, composition: dict, queries: list, ctx: ExecutionContext) -> list:
        raise NotImplementedError

    def index_stats(self) -> dict:
        """Build time/size when known; {} means unmeasured (explicit nulls downstream)."""
        return {}


class SimulatedExecutor(CompositionExecutor):
    """Deterministic synthetic executor: gold-driven hits + distractor noise.

    For each query, returns a fixed share of the gold set plus seeded
    distractors, with synthetic scores and timings. Two runs with the same
    seed produce identical output.
    """

    def __init__(self, seed: int, recall: float = 0.8):
        self.seed = seed
        self.recall = recall

    def _topology(self, composition: dict) -> str:
        if composition.get("topology"):
            return composition["topology"]
        return f"{composition.get('vector_provider', 'unknown')}_sim"

    def execute(self, composition: dict, queries: list, ctx: ExecutionContext) -> list:
        topology = self._topology(composition)
        results = []
        for query in queries:
            rng = random.Random(f"{self.seed}:{composition.get('composition_id')}:{query['query_id']}")
            gold = list(query.get("gold", []))
            eligible = list(query.get("eligible", gold))
            keep = max(1, int(len(gold) * self.recall)) if gold else 0
            hits = [
                {"chunk_id": cid, "rank": rank, "score": round(1.0 - rank * 0.05, 4)}
                for rank, cid in enumerate(gold[:keep])
            ]
            for extra in range(rng.randint(0, 3)):
                hits.append(
                    {
                        "chunk_id": f"distractor-{rng.randint(0, 9999):04d}",
                        "rank": len(hits),
                        "score": round(rng.uniform(0.0, 0.4), 4),
                    }
                )
            timing_ms = round(rng.uniform(5.0, 50.0), 3)
            results.append(
                RawQueryResult(
                    query_id=query["query_id"],
                    hits=hits,
                    eligible_set=eligible,
                    timing_ms=timing_ms,
                    timing_scope="simulated",
                    topology=topology,
                    structural_empty=not gold and not eligible,
                )
            )
        return results
