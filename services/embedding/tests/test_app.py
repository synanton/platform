from fastapi.testclient import TestClient

from app import create_app
from config import Settings
from model import load_model


class FakeEncoder:
    def __init__(self, dim: int = 4) -> None:
        self.dim = dim
        self.calls: list[list[str]] = []

    def encode(self, texts, normalize_embeddings=True, convert_to_numpy=True):
        self.calls.append(list(texts))
        return [[float((i + 1) / 10) for i in range(self.dim)] for _ in texts]


def settings(**overrides) -> Settings:
    values = {
        "model_path": "/models/fake",
        "model_name": "fake-model",
        "expected_dim": 4,
        "device": "cpu",
        "local_files_only": True,
        "max_batch_size": 3,
        "truncate_dim": 0,
        "port": 8000,
        "probe_text": "probe",
    }
    values.update(overrides)
    return Settings(**values)


def app_with(encoder: FakeEncoder, **overrides):
    active = settings(**overrides)
    return create_app(
        settings=active,
        loader=lambda: load_model(active, model_factory=lambda _: encoder),
    )


def test_endpoints_are_503_before_model_load() -> None:
    client = TestClient(app_with(FakeEncoder()))
    assert client.get("/health").status_code == 503
    assert client.get("/info").status_code == 503
    assert client.post("/embed", json={"texts": ["hello"]}).status_code == 503


def test_ready_health_and_measured_info() -> None:
    with TestClient(app_with(FakeEncoder(4))) as client:
        health = client.get("/health")
        assert health.status_code == 200
        assert health.json()["dimension"] == 4
        assert health.json()["backend"] == "cpu"
        assert health.json()["device_name"] is None

        info = client.get("/info")
        assert info.status_code == 200
        body = info.json()
        assert body["model_name"] == "fake-model"
        assert body["model_path"] == "/models/fake"
        assert body["dimension"] == 4
        assert body["dimension_source"] == "measured"
        assert body["backend"] == "cpu"
        assert body["device_name"] is None
        assert body["sample_vector_head"] == [0.1, 0.2, 0.3, 0.4]
        assert body["sample_vector_hash"].startswith("sha256:")
        assert body["truncation"] == {"enabled": False, "target_dim": None}
        assert body["max_batch_size"] == 3


def test_embed_single_and_batch_and_limits() -> None:
    with TestClient(app_with(FakeEncoder(4))) as client:
        single = client.post("/embed", json={"texts": ["hello"]})
        assert single.status_code == 200
        assert len(single.json()["vectors"]) == 1
        assert len(single.json()["vectors"][0]) == 4

        batch = client.post("/embed", json={"texts": ["a", "b", "c"]})
        assert batch.status_code == 200
        assert len(batch.json()["vectors"]) == 3

        assert client.post("/embed", json={"texts": ["a", "b", "c", "d"]}).status_code == 422
        assert client.post("/embed", json={"texts": []}).status_code == 422


def test_truncate_dim_is_reported_as_disabled(caplog) -> None:
    with TestClient(app_with(FakeEncoder(4), truncate_dim=1024)) as client:
        body = client.get("/info").json()
        assert body["truncation"] == {"enabled": False, "target_dim": None}
        assert "EMBED_TRUNCATE_DIM=1024 is ignored" in caplog.text
