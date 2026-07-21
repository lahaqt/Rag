from __future__ import annotations

from fastapi.testclient import TestClient

from evaluation_service import api
from evaluation_service.config import EvaluationSettings


def test_run_lookup_fails_closed_without_a_configured_key(monkeypatch) -> None:
    monkeypatch.setattr(api, "settings", EvaluationSettings(api_key=""))
    client = TestClient(api.app)

    response = client.get("/api/evaluations/runs/any-run")

    assert response.status_code == 401


def test_run_lookup_requires_the_configured_key(monkeypatch) -> None:
    monkeypatch.setattr(api, "settings", EvaluationSettings(api_key="test-key"))
    client = TestClient(api.app)

    assert client.get("/api/evaluations/runs/any-run").status_code == 401
    assert client.get("/api/evaluations/runs/any-run", headers={"X-Eval-Api-Key": "test-key"}).status_code == 404
