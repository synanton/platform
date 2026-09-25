"""Request pacing and a daily request budget for runs that go through the GPU plane.

On GPU-7 (OpenRouter free models) the limits are about 20 requests/minute and a daily
free-model quota (1,000/day for this key; G0, docs/research/retrieval-evaluation-benchmark-plan.md
§6 Phase B1-G). Each search can cost at most one embedding request, and none when synquest's
query cache serves it. The harness therefore:

* **paces** searches (``Throttle``). This keeps evaluation under the per-minute limit.
  Ingest is paced by the GPU-plane client inside synflux (``gpu-plane.max-requests-per-minute``);
* **budgets** requests per UTC day (``RequestBudget``). A local ledger file counts the
  requests this harness caused (measured for searches via ``embed_cached``; estimated for
  ingest), and the run stops before it would exceed the budget. A stopped run is partial, so
  it is invalid and not reported.

The ledger is advisory and local to this machine. OpenRouter's own counter
(``gpu7_check.py --usage``) is authoritative but lags, and is recorded next to it in the
run record.
"""

from __future__ import annotations

import json
import time
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_LEDGER_PATH = Path(__file__).resolve().parents[1] / ".cache" / "request-ledger.json"


class BudgetExhausted(RuntimeError):
    pass


def utc_day() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%d")


class RequestLedger:
    """``{"YYYY-MM-DD": {"<plane>": count}}`` in a small JSON file (git-ignored ``.cache/``)."""

    def __init__(self, path: Path = DEFAULT_LEDGER_PATH):
        self.path = Path(path)

    def _load(self) -> dict:
        if not self.path.is_file():
            return {}
        try:
            return json.loads(self.path.read_text())
        except json.JSONDecodeError:
            return {}

    def used_today(self, plane: str) -> int:
        return int(self._load().get(utc_day(), {}).get(plane, 0))

    def add(self, plane: str, n: int) -> None:
        if n <= 0:
            return
        data = self._load()
        day = data.setdefault(utc_day(), {})
        day[plane] = int(day.get(plane, 0)) + n
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")


class RequestBudget:
    """Stops a run before it exceeds ``daily_limit`` requests on ``plane`` today (0 = no limit)."""

    def __init__(self, ledger: RequestLedger, plane: str, daily_limit: int):
        self.ledger = ledger
        self.plane = plane
        self.daily_limit = daily_limit

    def remaining(self) -> int | None:
        if self.daily_limit <= 0:
            return None
        return self.daily_limit - self.ledger.used_today(self.plane)

    def ensure(self, n: int = 1) -> None:
        remaining = self.remaining()
        if remaining is not None and remaining < n:
            raise BudgetExhausted(
                f"daily request budget for {self.plane} exhausted "
                f"({self.ledger.used_today(self.plane)}/{self.daily_limit} used today, UTC {utc_day()})"
            )

    def record(self, n: int) -> None:
        self.ledger.add(self.plane, n)


class Throttle:
    """Evenly spaces calls: at most ``per_minute`` per minute (0 = unthrottled)."""

    def __init__(self, per_minute: int, clock=time.monotonic, sleep=time.sleep):
        self.interval = 60.0 / per_minute if per_minute > 0 else 0.0
        self._clock = clock
        self._sleep = sleep
        self._next = 0.0

    def wait(self) -> float:
        if self.interval == 0:
            return 0.0
        now = self._clock()
        slot = max(now, self._next)
        self._next = slot + self.interval
        delay = slot - now
        if delay > 0:
            self._sleep(delay)
        return delay
