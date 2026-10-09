"""Sink contract tests (BR-A1.2). Every ResultSink implementation — file now,
Kafka/ClickHouse in A3 — must satisfy this contract: valid documents persist
atomically, invalid documents are rejected before any write, and failures
leave no partial artifact.
"""

import json

import pytest

from benchmark_runner.sinks import (
    ClickHouseResultSink,
    FileResultSink,
    KafkaResultSink,
    SinkValidationError,
    SinkWriteError,
    run_completed_event,
)


class FakeProducer:
    """Duck-typed Kafka producer stand-in (send(topic, value: bytes))."""

    def __init__(self, fail=False):
        self.sent = []
        self.fail = fail

    def send(self, topic, value):
        if self.fail:
            raise ConnectionError("broker unreachable")
        self.sent.append((topic, value))


class FakeClickHouse:
    """Duck-typed ClickHouse client stand-in (insert(table, row: dict))."""

    def __init__(self, fail=False):
        self.rows = []
        self.fail = fail

    def insert(self, table, row):
        if self.fail:
            raise ConnectionError("server unreachable")
        self.rows.append((table, row))


def make_result(run_id="vec-test-r1"):
    return {
        "run_id": run_id,
        "corpus": "demo-frozen-v1",
        "queries": [
            {
                "query_id": "q000",
                "top_k": [{"chunk_id": "c1", "rank": 0, "score": 1.0}],
                "eligible_set": ["c1", "c2"],
                "timing_ms": 12.5,
                "timing_scope": "test",
            }
        ],
    }


def check_valid_document_persists(sink, read_back, filesystem=True):
    """Shared contract case 1: a valid document persists and round-trips."""
    path = sink.write(make_result())

    if filesystem:
        assert path.exists()
    assert json.loads(json.dumps(read_back(path)))["run_id"] == "vec-test-r1"
    assert read_back(path)["queries"][0]["top_k"][0]["chunk_id"] == "c1"


def check_invalid_document_rejected_before_write(sink, artifact_dir, count_artifacts):
    """Shared contract case 2: schema violations reject before any artifact exists."""
    before = count_artifacts(artifact_dir)
    bad = make_result()
    del bad["queries"][0]["top_k"]

    with pytest.raises(SinkValidationError):
        sink.write(bad)

    assert count_artifacts(artifact_dir) == before


def check_failure_leaves_no_partial_artifact(sink_factory, unwritable_dir):
    """Shared contract case 3: a failed write leaves no partial file behind."""
    sink = sink_factory(unwritable_dir)

    with pytest.raises(SinkWriteError):
        sink.write(make_result())

    leftovers = [
        p
        for p in unwritable_dir.iterdir()
        if p.suffix in (".tmp", ".json") and "vec-test" in p.name
    ]
    assert leftovers == []


def check_event_requires_all_fields(sink):
    """Shared contract case 4: malformed events are rejected loudly."""
    with pytest.raises(SinkWriteError):
        sink.emit({"run_id": "x"})


def check_event_round_trip(sink, read_event):
    """Shared contract case 5: well-formed events persist."""
    event = run_completed_event("vec-test-r1", "vec-6comp-r1", 6, 1200)
    sink.emit(event)

    assert read_event("vec-test-r1")["composition_count"] == 6


# --- FileResultSink bindings of the shared contract ---


def file_sink(tmp_path):
    return FileResultSink(tmp_path / "results")


def count_json_files(directory):
    return len([p for p in directory.iterdir() if p.suffix == ".json"]) if directory.exists() else 0


def test_file_valid_document_persists(tmp_path):
    sink = file_sink(tmp_path)
    check_valid_document_persists(sink, lambda p: json.loads(p.read_text()))


def test_file_invalid_document_rejected_before_write(tmp_path):
    sink = file_sink(tmp_path)
    check_invalid_document_rejected_before_write(sink, tmp_path / "results", count_json_files)


def test_file_failure_leaves_no_partial_artifact(tmp_path):
    # A regular file as base path: mkdir fails -> write fails before any artifact.
    blocker = tmp_path / "blocker"
    blocker.write_text("x")

    check_failure_leaves_no_partial_artifact(
        lambda _ignored: FileResultSink(blocker / "results"), tmp_path
    )


def test_file_event_requires_all_fields(tmp_path):
    check_event_requires_all_fields(file_sink(tmp_path))


def test_file_event_round_trip(tmp_path):
    sink = file_sink(tmp_path)
    check_event_round_trip(
        sink, lambda run_id: json.loads((tmp_path / "results" / f"{run_id}.event.json").read_text())
    )


# --- KafkaResultSink bindings (same contract; live broker is a follow-on) ---


def test_kafka_valid_document_persists():
    producer = FakeProducer()
    sink = KafkaResultSink(producer, "bench.results", "bench.events")
    check_valid_document_persists(
        sink, lambda p: json.loads(producer.sent[0][1].decode()), filesystem=False
    )
    assert producer.sent[0][0] == "bench.results"


def test_kafka_invalid_document_rejected_before_write(tmp_path):
    producer = FakeProducer()
    sink = KafkaResultSink(producer, "bench.results", "bench.events")
    check_invalid_document_rejected_before_write(sink, tmp_path, lambda d: len(producer.sent))


def test_kafka_failure_raises_loudly():
    sink = KafkaResultSink(FakeProducer(fail=True), "bench.results", "bench.events")

    with pytest.raises(SinkWriteError):
        sink.write(make_result())


def test_kafka_event_requires_all_fields():
    check_event_requires_all_fields(
        KafkaResultSink(FakeProducer(), "bench.results", "bench.events")
    )


def test_kafka_event_round_trip():
    producer = FakeProducer()
    sink = KafkaResultSink(producer, "bench.results", "bench.events")
    check_event_round_trip(
        sink,
        lambda run_id: json.loads(
            [v for t, v in producer.sent if t == "bench.events"][0].decode()
        ),
    )


# --- ClickHouseResultSink bindings (same contract; live server is a follow-on) ---


def test_clickhouse_valid_document_persists():
    client = FakeClickHouse()
    sink = ClickHouseResultSink(client, "bench_results")
    check_valid_document_persists(sink, lambda p: client.rows[0][1], filesystem=False)
    assert client.rows[0][0] == "bench_results"


def test_clickhouse_invalid_document_rejected_before_write(tmp_path):
    client = FakeClickHouse()
    sink = ClickHouseResultSink(client, "bench_results")
    check_invalid_document_rejected_before_write(sink, tmp_path, lambda d: len(client.rows))


def test_clickhouse_failure_raises_loudly():
    sink = ClickHouseResultSink(FakeClickHouse(fail=True), "bench_results")

    with pytest.raises(SinkWriteError):
        sink.write(make_result())


def test_clickhouse_event_round_trip():
    client = FakeClickHouse()
    sink = ClickHouseResultSink(client, "bench_results")
    check_event_round_trip(
        sink,
        lambda run_id: [r for t, r in client.rows if t == "bench_results_events"][0],
    )
