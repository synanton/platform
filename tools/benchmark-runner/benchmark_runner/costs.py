"""Cost controls (BR-A1.4): dry-run, timeout, max-event-count guard.

All controls are manifest-configurable (cost_controls section) and fire loudly
when triggered. The runner honors them; this module implements the mechanics.
"""

from __future__ import annotations

import concurrent.futures


class CostControlError(Exception):
    """Base class; a fired control aborts the run loudly."""


class CostTimeout(CostControlError):
    def __init__(self, timeout_ms: int):
        super().__init__(f"cost control fired: timeout after {timeout_ms} ms")
        self.timeout_ms = timeout_ms


class EventCountExceeded(CostControlError):
    def __init__(self, limit: int, observed: int):
        super().__init__(
            f"cost control fired: event count {observed} exceeded limit {limit}"
        )
        self.limit = limit
        self.observed = observed


class EventCounter:
    """Counts ingested/emitted events; raises on the first event past the limit."""

    def __init__(self, limit: int):
        if limit < 1:
            raise ValueError("max_event_count must be >= 1")
        self.limit = limit
        self.count = 0

    def observe(self, n: int = 1) -> int:
        self.count += n
        if self.count > self.limit:
            raise EventCountExceeded(self.limit, self.count)
        return self.count


def run_with_timeout(func, timeout_ms: int):
    """Run a zero-arg callable; raise CostTimeout if it exceeds timeout_ms."""
    if timeout_ms < 1:
        raise ValueError("timeout_ms must be >= 1")
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(func)
        try:
            return future.result(timeout=timeout_ms / 1000.0)
        except concurrent.futures.TimeoutError as e:
            future.cancel()
            raise CostTimeout(timeout_ms) from e


def is_dry_run(manifest: dict) -> bool:
    """True when the manifest requests validate-only (no artifacts)."""
    return bool(manifest.get("cost_controls", {}).get("dry_run", False))


def controls_from_manifest(manifest: dict) -> dict:
    """Extract effective controls with runner defaults for absent keys."""
    section = manifest.get("cost_controls", {}) or {}
    return {
        "dry_run": bool(section.get("dry_run", False)),
        "timeout_ms": section.get("timeout_ms"),
        "max_event_count": section.get("max_event_count"),
    }
