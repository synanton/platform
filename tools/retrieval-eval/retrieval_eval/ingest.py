"""Ingestion driver - wraps synflux's existing REST trigger.

Mirrors the exact request/poll shape scripts/run-extract-index-poc.sh and
scripts/run-ingestion-demo.sh already use:

    POST {synflux}/ingest/run   {"tenant": ..., "source": "filesystem", "path": ...}
    GET  {synflux}/ingest/jobs/{jobId}?tenant=...   -> state/processedCount/errorCount
    POST {synquest}/reindex?tenant=...

This module does not start or configure the compose stack itself (see
compose.py's docstring) - callers are expected to have already run
scripts/run-extract-index-poc.sh, or started the equivalent services.
"""

from __future__ import annotations

import time
from dataclasses import dataclass

import requests

DEFAULT_INGEST_PATH = "/demo-data/documents"
POLL_INTERVAL_SECONDS = 1
POLL_TIMEOUT_SECONDS = 180


class IngestionFailed(RuntimeError):
    pass


class IngestionTimedOut(RuntimeError):
    pass


@dataclass(frozen=True)
class IngestionResult:
    job_id: str
    processed_count: int
    error_count: int


def start_ingestion(synflux_base_url: str, tenant: str, path: str = DEFAULT_INGEST_PATH) -> str:
    """POST /ingest/run and return the jobId."""
    response = requests.post(
        f"{synflux_base_url}/ingest/run",
        json={"tenant": tenant, "source": "filesystem", "path": path},
        timeout=30,
    )
    response.raise_for_status()
    return response.json()["jobId"]


def wait_for_ingestion(
    synflux_base_url: str,
    tenant: str,
    job_id: str,
    poll_interval_seconds: int = POLL_INTERVAL_SECONDS,
    timeout_seconds: int = POLL_TIMEOUT_SECONDS,
) -> IngestionResult:
    """Poll /ingest/jobs/{job_id} until SUCCEEDED, FAILED, or timeout."""
    deadline = time.monotonic() + timeout_seconds
    while True:
        response = requests.get(
            f"{synflux_base_url}/ingest/jobs/{job_id}",
            params={"tenant": tenant},
            timeout=30,
        )
        response.raise_for_status()
        job = response.json()
        state = job["state"]

        if state == "SUCCEEDED":
            return IngestionResult(
                job_id=job_id,
                processed_count=job["processedCount"],
                error_count=job["errorCount"],
            )
        if state == "FAILED":
            raise IngestionFailed(
                f"ingestion job {job_id} FAILED "
                f"(processed={job.get('processedCount')}, errors={job.get('errorCount')})"
            )
        if time.monotonic() > deadline:
            raise IngestionTimedOut(f"ingestion job {job_id} did not complete within {timeout_seconds}s")
        time.sleep(poll_interval_seconds)


def ingest(synflux_base_url: str, tenant: str, path: str = DEFAULT_INGEST_PATH) -> IngestionResult:
    """Start ingestion and block until it completes. Raises on failure/timeout."""
    job_id = start_ingestion(synflux_base_url, tenant, path)
    return wait_for_ingestion(synflux_base_url, tenant, job_id)


def reindex(synquest_base_url: str, tenant: str) -> None:
    """POST /reindex - must run after ingest() before search() sees new content."""
    response = requests.post(
        f"{synquest_base_url}/reindex",
        params={"tenant": tenant},
        timeout=60,
    )
    response.raise_for_status()
