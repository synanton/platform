"""Provider spend / quota probe for GPU-plane runs.

The platform never holds the provider key. Spend is read by running an external command
that prints one JSON line, by default gpu-runtime's
``tools/gpu7-check/gpu7_check.py --usage``:

    {"usage": 0.00052275, "usage_daily": 0, ..., "free_model_daily_requests": {"used": 62, ...}}

The run record stores the before/after snapshots. Any increase in ``usage`` makes a
free-models run invalid.
"""

from __future__ import annotations

import json
import shlex
import subprocess
from dataclasses import dataclass


class SpendProbeFailed(RuntimeError):
    pass


@dataclass(frozen=True)
class SpendSnapshot:
    usage_usd: float
    free_requests_used: int | None

    def as_dict(self) -> dict:
        return {"usage_usd": self.usage_usd, "free_requests_used": self.free_requests_used}


def parse_snapshot(output: str) -> SpendSnapshot:
    lines = [line for line in output.strip().splitlines() if line.strip().startswith("{")]
    if not lines:
        raise SpendProbeFailed(f"spend command printed no JSON line: {output[-200:]!r}")
    data = json.loads(lines[-1])
    if "usage" not in data:
        raise SpendProbeFailed("spend JSON has no 'usage' field")
    free = (data.get("free_model_daily_requests") or {}).get("used")
    return SpendSnapshot(usage_usd=float(data["usage"]), free_requests_used=int(free) if free is not None else None)


def snapshot(command: str, timeout: int = 60) -> SpendSnapshot:
    proc = subprocess.run(shlex.split(command), capture_output=True, text=True, timeout=timeout)
    if proc.returncode != 0:
        raise SpendProbeFailed(f"spend command exited {proc.returncode}: {proc.stderr.strip()[-200:]}")
    return parse_snapshot(proc.stdout)
