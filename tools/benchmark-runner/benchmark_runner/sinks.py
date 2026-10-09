"""Result sinks (BR-A1.2): ResultSink abstraction + FileResultSink.

Sinks are the only writers of Result Manifest artifacts: an artifact exists
iff the run completed successfully. All sinks honor the same contract
(tests/test_sink_contract.py); Kafka/ClickHouse implementations arrive in A3
against the identical contract.
"""

from __future__ import annotations

import abc
import json
import os
import tempfile
from pathlib import Path

import jsonschema

REPO_ROOT = Path(__file__).resolve().parents[3]
RESULT_SCHEMA_PATH = REPO_ROOT / "schemas" / "benchmark" / "result-manifest.schema.json"


class SinkError(Exception):
    """Base class for sink failures. A failed write leaves no partial artifact."""


class SinkWriteError(SinkError):
    def __init__(self, path: Path, reason: str):
        super().__init__(f"sink write failed: {path}: {reason}")
        self.path = path


class SinkValidationError(SinkError):
    """Result document violates result-manifest.schema.json; nothing written."""

    def __init__(self, reason: str):
        super().__init__(f"result document invalid: {reason}")


class ResultSink(abc.ABC):
    """Pluggable result writer. Implementations: file (A1), Kafka/ClickHouse (A3)."""

    @abc.abstractmethod
    def write(self, result: dict) -> Path:
        """Validate and persist one Result Manifest. Returns the artifact path."""

    @abc.abstractmethod
    def emit(self, event: dict) -> None:
        """Emit one RunCompletedEvent (provisional shape pending Eventing 1.27)."""


def validate_result(result: dict, schema_path: Path = RESULT_SCHEMA_PATH) -> None:
    """Fail loud if the document violates the Result Manifest schema."""
    try:
        schema = json.loads(schema_path.read_text())
    except (OSError, json.JSONDecodeError) as e:
        raise SinkWriteError(schema_path, f"cannot load result schema: {e}") from e
    validator = jsonschema.Draft202012Validator(schema)
    errors = sorted(validator.iter_errors(result), key=lambda e: list(e.absolute_path))
    if errors:
        first = errors[0]
        path = ".".join(str(p) for p in first.absolute_path) or "(root)"
        raise SinkValidationError(f"'{path}': {first.message}")


def run_completed_event(
    run_id: str, manifest_id: str, composition_count: int, duration_ms: int
) -> dict:
    """Provisional RunCompletedEvent shape (pending Eventing 1.27 freeze)."""
    return {
        "run_id": run_id,
        "manifest_id": manifest_id,
        "composition_count": composition_count,
        "duration_ms": duration_ms,
    }


class KafkaResultSink(ResultSink):
    """Emits Result Manifests + events to Kafka (BR-A3.1, optional).

    Takes a duck-typed producer (anything with ``send(topic, value: bytes)``)
    so the contract holds without a broker or client library. Live wiring
    (kafka-python/confluent-kafka + broker address from manifest) is a
    follow-on gated on EventLab integration.
    """

    def __init__(self, producer, result_topic: str, event_topic: str):
        self.producer = producer
        self.result_topic = result_topic
        self.event_topic = event_topic

    def write(self, result: dict) -> Path:
        validate_result(result)
        payload = json.dumps(result, sort_keys=True).encode()
        try:
            self.producer.send(self.result_topic, payload)
        except Exception as e:
            raise SinkWriteError(Path(f"kafka://{self.result_topic}"), str(e)) from e
        return Path(f"kafka://{self.result_topic}/{result.get('run_id', 'unknown')}")

    def emit(self, event: dict) -> None:
        for field in ("run_id", "manifest_id", "composition_count", "duration_ms"):
            if field not in event:
                raise SinkWriteError(
                    Path(f"kafka://{self.event_topic}"),
                    f"RunCompletedEvent missing field: {field}",
                )
        try:
            self.producer.send(
                self.event_topic, json.dumps(event, sort_keys=True).encode()
            )
        except Exception as e:
            raise SinkWriteError(Path(f"kafka://{self.event_topic}"), str(e)) from e


class ClickHouseResultSink(ResultSink):
    """Inserts Result Manifests into ClickHouse (BR-A3.1, optional).

    Takes a duck-typed client (anything with ``insert(table, row: dict)``).
    Live wiring (clickhouse-connect + server address) is a follow-on gated
    on analytics integration.
    """

    def __init__(self, client, table: str):
        self.client = client
        self.table = table

    def write(self, result: dict) -> Path:
        validate_result(result)
        try:
            self.client.insert(self.table, result)
        except Exception as e:
            raise SinkWriteError(Path(f"clickhouse://{self.table}"), str(e)) from e
        return Path(f"clickhouse://{self.table}/{result.get('run_id', 'unknown')}")

    def emit(self, event: dict) -> None:
        # Analytics sink: events ride the same insert path as results.
        try:
            self.client.insert(f"{self.table}_events", event)
        except Exception as e:
            raise SinkWriteError(Path(f"clickhouse://{self.table}_events"), str(e)) from e
class FileResultSink(ResultSink):
    """Writes Result Manifests to disk. Atomic write (temp file + os.replace)."""

    def __init__(self, base_path: Path):
        self.base_path = Path(base_path)

    def _artifact_path(self, result: dict) -> Path:
        run_id = result.get("run_id", "unknown")
        safe = "".join(c if c.isalnum() or c in "-_." else "_" for c in str(run_id))
        return self.base_path / f"{safe}.result.json"

    def write(self, result: dict) -> Path:
        validate_result(result)
        try:
            self.base_path.mkdir(parents=True, exist_ok=True)
        except OSError as e:
            raise SinkWriteError(self.base_path, f"cannot create directory: {e}") from e
        target = self._artifact_path(result)
        payload = json.dumps(result, indent=2, sort_keys=True)
        try:
            fd, tmp_name = tempfile.mkstemp(
                dir=str(self.base_path), prefix=target.name + ".", suffix=".tmp"
            )
            try:
                with open(fd, "w") as handle:
                    handle.write(payload)
                    handle.flush()
                    os.fsync(handle.fileno())
                os.replace(tmp_name, target)
            except BaseException:
                try:
                    os.unlink(tmp_name)
                except OSError:
                    pass
                raise
        except SinkError:
            raise
        except OSError as e:
            raise SinkWriteError(target, str(e)) from e
        return target

    def emit(self, event: dict) -> None:
        for field in ("run_id", "manifest_id", "composition_count", "duration_ms"):
            if field not in event:
                raise SinkWriteError(
                    self.base_path, f"RunCompletedEvent missing field: {field}"
                )
        path = self.base_path / f"{event['run_id']}.event.json"
        try:
            self.base_path.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(event, indent=2, sort_keys=True))
        except OSError as e:
            raise SinkWriteError(path, str(e)) from e
