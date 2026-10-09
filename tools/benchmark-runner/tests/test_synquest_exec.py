"""B5.2 machinery tests: REST executor against an in-process stub server (no live stack)."""

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from benchmark_runner.synquest_exec import (
    EndpointUnreachable,
    SearchFailed,
    SynquestExecutor,
    post_search,
    probe,
)

STUB_HITS = [
    {"content_ref_id": "aaa", "chunk_ordinal": 0, "score": 2.0},
    {"content_ref_id": "bbb", "chunk_ordinal": 1, "score": 1.0},
]


class StubHandler(BaseHTTPRequestHandler):
    mode = "ok"
    seen = []

    def log_message(self, *args):
        pass

    def _send(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        StubHandler.seen.append(json.loads(self.rfile.read(length).decode() or "{}"))
        if self.path == "/search":
            if StubHandler.mode == "error":
                self._send(500, {"error": "boom"})
            else:
                self._send(
                    200,
                    {"hits": STUB_HITS, "trace": {"totalMs": 11.0},
                     "query_usage": {}},
                )
        else:
            self._send(404, {})

    def do_GET(self):
        if self.path == "/actuator/health":
            self._send(200, {"status": "UP"})
        else:
            self._send(404, {})


@pytest.fixture()
def stub_url():
    StubHandler.mode = "ok"
    StubHandler.seen = []
    server = HTTPServer(("127.0.0.1", 0), StubHandler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{port}"
    server.shutdown()


def test_post_search_returns_hits(stub_url):
    response = post_search(stub_url, {"query": "q"})

    assert [h["content_ref_id"] for h in response["hits"]] == ["aaa", "bbb"]
    assert StubHandler.seen[0]["query"] == "q"


def test_post_search_server_error_raises_named(stub_url):
    StubHandler.mode = "error"

    with pytest.raises(SearchFailed) as exc_info:
        post_search(stub_url, {"query": "q"})
    assert exc_info.value.status == 500


def test_post_search_closed_port_raises_named():
    with pytest.raises(EndpointUnreachable):
        post_search("http://127.0.0.1:9", {"query": "q"}, timeout_s=2)


def test_probe_never_raises():
    up = probe("http://127.0.0.1:9", timeout_s=1)

    assert up["reachable"] is False
    assert "reason" in up


def test_probe_reports_up(stub_url):
    assert probe(stub_url)["reachable"] is True


def test_executor_maps_hits_and_topology(stub_url):
    executor = SynquestExecutor(stub_url)
    composition = {"composition_id": "c1", "vector_provider": "lucene"}
    queries = [
        {"query_id": "q1", "text": "hello", "gold": ["aaa"],
         "eligible": ["aaa", "bbb"], "top_k": 10}
    ]

    results = executor.execute(composition, queries, None)

    assert len(results) == 1
    assert [h["chunk_id"] for h in results[0].hits] == ["aaa", "bbb"]
    assert results[0].topology == "lucene"
    assert results[0].eligible_set == ["aaa", "bbb"]
    assert results[0].structural_empty is False


def test_executor_structural_empty_without_gold_or_eligible(stub_url):
    executor = SynquestExecutor(stub_url)

    results = executor.execute(
        {"composition_id": "c1"}, [{"query_id": "qn"}], None
    )

    assert results[0].structural_empty is True
