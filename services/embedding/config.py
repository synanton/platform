"""Embedding service configuration.

Defaults target the homelab: the model is mounted locally on each node, so no
Hugging Face download happens at startup. Set EMBED_MODEL to a repo id and
MODEL_LOCAL_FILES_ONLY=false for environments that resolve models from the Hub.
"""

from __future__ import annotations

import os
from dataclasses import dataclass


def _bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _int(name: str, default: int) -> int:
    value = os.getenv(name)
    return default if value is None or not value.strip() else int(value)


@dataclass(frozen=True)
class Settings:
    model_path: str
    model_name: str
    expected_dim: int
    device: str
    local_files_only: bool
    max_batch_size: int
    truncate_dim: int
    port: int
    probe_text: str

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            model_path=os.getenv(
                "EMBED_MODEL", "/mnt/local-fast/models/bge-small-en-v1.5"
            ),
            model_name=os.getenv("EMBED_MODEL_NAME", "BAAI/bge-small-en-v1.5"),
            expected_dim=_int("EMBED_DIM", 384),
            device=os.getenv("EMBED_DEVICE", "cpu").strip().lower(),
            local_files_only=_bool("MODEL_LOCAL_FILES_ONLY", True),
            max_batch_size=_int("MAX_BATCH_SIZE", 100),
            truncate_dim=_int("EMBED_TRUNCATE_DIM", 0),
            port=_int("EMBEDDING_PORT", 8000),
            probe_text=os.getenv("EMBED_PROBE_TEXT", "dimension probe"),
        )
