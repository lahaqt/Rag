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


def test_agent_url_override_is_disabled_by_default(monkeypatch) -> None:
    monkeypatch.setattr(api, "settings", EvaluationSettings(agent_base_url="http://127.0.0.1:28083"))

    response = TestClient(api.app).post(
        "/api/evaluations/runs",
        headers={"X-Eval-Api-Key": ""},
        json={"datasetPath": "ignored.jsonl", "agentBaseUrl": "http://169.254.169.254"},
    )

    assert response.status_code == 401
    try:
        api.resolve_agent_base_url("http://169.254.169.254")
    except api.HTTPException as error:
        assert error.status_code == 400
    else:
        raise AssertionError("agent URL override must be disabled")
