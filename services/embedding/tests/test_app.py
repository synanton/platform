from fastapi.testclient import TestClient

from app import app

client = TestClient(app)


def test_health_is_503_until_model_loads() -> None:
    response = client.get("/health")
    assert response.status_code == 503
    assert response.json()["detail"] == "model not loaded"


def test_info_is_503_until_model_loads() -> None:
    response = client.get("/info")
    assert response.status_code == 503


def test_embed_is_503_until_model_loads() -> None:
    response = client.post("/embed", json={"texts": ["hello world"]})
    assert response.status_code == 503
