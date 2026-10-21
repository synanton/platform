import pytest

from config import Settings
from model import DeviceUnavailableError, DimensionMismatchError, load_model


class FakeEncoder:
    def __init__(self, dim: int = 4) -> None:
        self.dim = dim

    def encode(self, texts, normalize_embeddings=True, convert_to_numpy=True):
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


def test_measured_dimension_mismatch_fails() -> None:
    with pytest.raises(DimensionMismatchError, match="EMBED_DIM=8"):
        load_model(settings(expected_dim=8), model_factory=lambda _: FakeEncoder(4))


def test_cuda_unavailable_fails_loudly() -> None:
    with pytest.raises(DeviceUnavailableError, match="CUDA is not available"):
        load_model(
            settings(device="cuda"),
            model_factory=lambda _: FakeEncoder(4),
            cuda_available=lambda: False,
        )


def test_cuda_ready_reports_backend_and_device() -> None:
    model = load_model(
        settings(device="cuda"),
        model_factory=lambda _: FakeEncoder(4),
        cuda_available=lambda: True,
        device_name=lambda _: "Fake GPU",
    )
    assert model.backend == "cuda"
    assert model.device_name == "Fake GPU"
    assert model.dimension == 4


def test_probe_hash_is_stable() -> None:
    first = load_model(settings(), model_factory=lambda _: FakeEncoder(4))
    second = load_model(settings(), model_factory=lambda _: FakeEncoder(4))
    assert first.sample_vector_hash == second.sample_vector_hash


def test_defaults_target_the_local_model(monkeypatch) -> None:
    for name in [
        "EMBED_MODEL",
        "EMBED_MODEL_NAME",
        "EMBED_DIM",
        "EMBED_DEVICE",
        "MODEL_LOCAL_FILES_ONLY",
        "MAX_BATCH_SIZE",
        "EMBED_TRUNCATE_DIM",
        "EMBEDDING_PORT",
        "EMBED_PROBE_TEXT",
    ]:
        monkeypatch.delenv(name, raising=False)

    active = Settings.from_env()
    assert active.model_path == "/mnt/local-fast/models/bge-small-en-v1.5"
    assert active.expected_dim == 384
    assert active.local_files_only is True
    assert active.device == "cpu"
