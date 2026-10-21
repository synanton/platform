"""Synanton embedding service.

CPU/GPU embedding service for the vector path (#349). It loads one small model,
measures the probe vector at startup, and fails hard if the measured dimension
does not match EMBED_DIM. The service never truncates and never changes the
vector shape.

The deployment chooses the device via EMBED_DEVICE; the service honors it and
fails loudly if the device is unavailable. It does not probe-and-pick a device.
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from typing import Callable

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field

from config import Settings
from model import LoadedModel, load_model

LOG = logging.getLogger("embedding-service")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)

Loader = Callable[[], LoadedModel]


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings: Settings = app.state.settings
    LOG.info(
        "embedding service starting model=%s path=%s embed_dim=%d device=%s "
        "local_files_only=%s max_batch=%d",
        settings.model_name,
        settings.model_path,
        settings.expected_dim,
        settings.device,
        settings.local_files_only,
        settings.max_batch_size,
    )
    if settings.truncate_dim > 0:
        LOG.warning(
            "EMBED_TRUNCATE_DIM=%d is ignored; this service emits native dimensions only",
            settings.truncate_dim,
        )
    model = app.state.loader()
    app.state.model = model
    LOG.info(
        "embedding service ready model=%s path=%s measured_dim=%d EMBED_DIM=%d "
        "backend=%s device=%r truncation=off",
        model.name,
        model.path,
        model.dimension,
        settings.expected_dim,
        model.backend,
        model.device_name,
    )
    yield


class EmbedRequest(BaseModel):
    texts: list[str] = Field(default_factory=list)


def _model(request: Request) -> LoadedModel:
    model = getattr(request.app.state, "model", None)
    if model is None:
        raise HTTPException(status_code=503, detail="model not loaded")
    return model


def create_app(
    settings: Settings | None = None,
    loader: Loader | None = None,
) -> FastAPI:
    settings = settings or Settings.from_env()

    def default_loader() -> LoadedModel:
        return load_model(settings)

    app = FastAPI(
        title="Synanton embedding service",
        version="0.2.0",
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.loader = loader or default_loader
    app.state.model = None

    @app.get("/health")
    def health(request: Request) -> dict:
        model = _model(request)
        return {
            "status": "ok",
            "model_name": model.name,
            "dimension": model.dimension,
            "backend": model.backend,
            "device_name": model.device_name,
        }

    @app.get("/info")
    def info(request: Request) -> dict:
        model = _model(request)
        active: Settings = request.app.state.settings
        return {
            "model_name": model.name,
            "model_path": model.path,
            "dimension": model.dimension,
            "dimension_source": "measured",
            "backend": model.backend,
            "device_name": model.device_name,
            "sample_vector_head": model.sample_vector_head,
            "sample_vector_hash": model.sample_vector_hash,
            "truncation": {"enabled": False, "target_dim": None},
            "max_batch_size": active.max_batch_size,
        }

    @app.post("/embed")
    def embed(request: Request, payload: EmbedRequest) -> dict:
        model = _model(request)
        active: Settings = request.app.state.settings
        if not payload.texts:
            raise HTTPException(status_code=422, detail="texts must not be empty")
        if len(payload.texts) > active.max_batch_size:
            raise HTTPException(
                status_code=422,
                detail=f"batch size exceeds MAX_BATCH_SIZE={active.max_batch_size}",
            )
        return {
            "vectors": model.embed(payload.texts),
            "model": model.name,
            "dimension": model.dimension,
        }

    return app


app = create_app()
