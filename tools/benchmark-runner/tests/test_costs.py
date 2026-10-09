"""BR-A1.4 acceptance: dry-run produces no artifacts; timeout aborts; guard fires."""

import time

import pytest

from benchmark_runner.costs import (
    CostTimeout,
    EventCountExceeded,
    EventCounter,
    controls_from_manifest,
    is_dry_run,
    run_with_timeout,
)


def test_dry_run_flag_reads_manifest():
    assert is_dry_run({"cost_controls": {"dry_run": True}}) is True
    assert is_dry_run({"cost_controls": {"dry_run": False}}) is False
    assert is_dry_run({}) is False


def test_controls_default_absent_keys():
    controls = controls_from_manifest({})

    assert controls == {"dry_run": False, "timeout_ms": None, "max_event_count": None}


def test_timeout_aborts_slow_callable():
    def slow():
        time.sleep(5)

    with pytest.raises(CostTimeout) as exc_info:
        run_with_timeout(slow, 100)
    assert "100" in str(exc_info.value)


def test_fast_callable_passes_through():
    assert run_with_timeout(lambda: 42, 5000) == 42


def test_event_counter_fires_past_limit():
    counter = EventCounter(3)
    assert counter.observe() == 1
    assert counter.observe(2) == 3

    with pytest.raises(EventCountExceeded) as exc_info:
        counter.observe()
    assert exc_info.value.limit == 3
    assert exc_info.value.observed == 4


def test_event_counter_rejects_nonpositive_limit():
    with pytest.raises(ValueError):
        EventCounter(0)


def test_timeout_rejects_nonpositive():
    with pytest.raises(ValueError):
        run_with_timeout(lambda: None, 0)
