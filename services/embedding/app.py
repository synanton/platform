"""Synanton embedding service (D1 skeleton).

The model is not loaded yet in D1: /health, /info and /embed return 503 until
D2. The endpoint contracts are fixed now so #348 can integrate against a stable
shape. The service never truncates; it reports native model dimensions only.
"""

import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

LOG = logging.getLogger("embedding-service")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)

MODEL_NAME = os.getenv("EMBED_MODEL", "BAAI/bge-small-en-v1.5")
EMBED_DIM = int(os.getenv("EMBED_DIM", "384"))
MAX_BATCH_SIZE = int(os.getenv("MAX_BATCH_SIZE", "100"))

# D2 flips this after the model loads and the probe embedding matches EMBED_DIM.
MODEL_LOADED = False

@asynccontextmanager
async def lifespan(_app: FastAPI):
    LOG.info(
        "embedding service starting model=%s embed_dim=%d max_batch=%d model_loaded=%s",
        MODEL_NAME,
        EMBED_DIM,
        MAX_BATCH_SIZE,
        MODEL_LOADED,
    )
    yield


app = FastAPI(title="Synanton embedding service", version="0.1.0", lifespan=lifespan)


class EmbedRequest(BaseModel):
    texts: list[str] = Field(default_factory=list)


def _require_loaded() -> None:
    if not MODEL_LOADED:
        raise HTTPException(status_code=503, detail="model not loaded")


@app.get("/health")
def health() -> dict:
    _require_loaded()
    return {"status": "ok", "model_name": MODEL_NAME, "dimension": EMBED_DIM}


@app.get("/info")
def info() -> dict:
    _require_loaded()
    # D2 replaces the configured dimension with the measured one and adds the
    # probe vector head/hash.
    return {
        "model_name": MODEL_NAME,
        "dimension": EMBED_DIM,
        "dimension_source": "config",
        "sample_vector_head": [],
        "sample_vector_hash": "",
        "truncation": {"enabled": False, "target_dim": None},
    }


@app.post("/embed")
def embed(request: EmbedRequest) -> dict:
    _require_loaded()
    if not request.texts:
        raise HTTPException(status_code=422, detail="texts must not be empty")
    if len(request.texts) > MAX_BATCH_SIZE:
        raise HTTPException(
            status_code=422,
            detail=f"batch size exceeds MAX_BATCH_SIZE={MAX_BATCH_SIZE}",
        )
    # D3 adds inference.
    raise HTTPException(status_code=503, detail="model not loaded")



