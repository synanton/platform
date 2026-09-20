import os
from pathlib import Path

from extraction_probe.config import load_config

REPO_CONFIG = Path(__file__).resolve().parents[1] / "config.yml"


def test_load_config_defaults(monkeypatch):
    for var in (
        "EXTRACTION_GATEWAY_HOST", "EXTRACTION_GATEWAY_GRPC_PORT",
        "EXTRACTION_OBJECTSTORE_ENDPOINT", "EXTRACTION_OBJECTSTORE_ACCESS_KEY",
        "EXTRACTION_OBJECTSTORE_SECRET_KEY", "EXTRACTION_PROBE_BUCKET",
        "EXTRACTION_OBJECTSTORE_REGION",
    ):
        monkeypatch.delenv(var, raising=False)

    config = load_config(REPO_CONFIG)
    assert config.extraction_gateway.host == "localhost"
    assert config.extraction_gateway.grpc_port == 9091
    assert config.extraction_gateway.target == "localhost:9091"
    assert config.objectstore.endpoint == "http://localhost:9000"
    assert config.objectstore.bucket == "extraction-probe"


def test_load_config_env_overrides(monkeypatch):
    monkeypatch.setenv("EXTRACTION_GATEWAY_HOST", "extraction-gateway")
    monkeypatch.setenv("EXTRACTION_GATEWAY_GRPC_PORT", "19091")
    monkeypatch.setenv("EXTRACTION_PROBE_BUCKET", "my-bucket")

    config = load_config(REPO_CONFIG)
    assert config.extraction_gateway.target == "extraction-gateway:19091"
    assert config.objectstore.bucket == "my-bucket"


def test_proto_source_dir_resolves_to_real_directory():
    config = load_config(REPO_CONFIG)
    expected = config.proto_source_dir / "synanton" / "extraction" / "v1" / "extraction_service.proto"
    assert expected.is_file(), f"expected real proto file at {expected}"
