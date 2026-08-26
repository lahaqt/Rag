from __future__ import annotations

import asyncio

import httpx
import respx

from evaluation_service.authoring_client import AuthoringClient


@respx.mock
def test_client_creates_and_polls_an_asynchronous_review() -> None:
    queued = {"id": "run-1", "revisionId": "rev-1", "status": "QUEUED", "currentPhase": "queued"}
    completed = {**queued, "status": "COMPLETED", "currentPhase": "aggregate", "review": {"id": "review-1"}}
    create = respx.post("http://authoring/api/v1/revisions/rev-1/review-runs").mock(
        return_value=httpx.Response(202, json=queued)
    )
    poll = respx.get("http://authoring/api/v1/review-runs/run-1").mock(
        return_value=httpx.Response(200, json=completed)
    )

    run = asyncio.run(AuthoringClient("http://authoring", "student-token", 2).run_revision("rev-1", "case-1"))

    assert run.status == "COMPLETED"
    assert run.review == {"id": "review-1"}
    assert create.calls[0].request.headers["Idempotency-Key"] == "case-1"
    assert poll.called
