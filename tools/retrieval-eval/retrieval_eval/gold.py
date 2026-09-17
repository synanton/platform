"""Gold query set loading.

Reads the same JSONL schema docs/research/flat-vs-semantic-chunks-research-plan.md
§3.2 already defined (query_id, question, gold_answer, gold_chunk_ids,
gold_section_paths, category), so both plans' harnesses can share query files
without a format translation step:

    {"query_id": "q001", "question": "...", "gold_answer": "...",
     "gold_chunk_ids": ["<ref>#0", "<ref>#3"],
     "gold_section_paths": ["Supply Chain", "Europe"], "category": "section-local"}
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class GoldQuery:
    query_id: str
    question: str
    gold_answer: str
    gold_chunk_ids: set[str]
    gold_section_paths: list[str]
    category: str


def load_gold_queries(path: Path | str) -> list[GoldQuery]:
    path = Path(path)
    queries: list[GoldQuery] = []
    with path.open() as f:
        for line_number, line in enumerate(f, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_number}: invalid JSON - {exc}") from exc

            queries.append(
                GoldQuery(
                    query_id=row["query_id"],
                    question=row["question"],
                    gold_answer=row.get("gold_answer", ""),
                    gold_chunk_ids=set(row.get("gold_chunk_ids", [])),
                    gold_section_paths=row.get("gold_section_paths", []),
                    category=row.get("category", "uncategorized"),
                )
            )
    return queries
