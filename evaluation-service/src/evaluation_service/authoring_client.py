from __future__ import annotations

import asyncio
from time import monotonic
from uuid import uuid4

import httpx

from .schemas import LiveReviewRun


class AuthoringClient:
    """Black-box client for the asynchronous Authoring Coach review contract."""

    def __init__(self, base_url: str, access_token: str, timeout_seconds: float = 180.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.access_token = access_token
        self.timeout_seconds = timeout_seconds

    async def run_revision(self, revision_id: str, idempotency_key: str | None = None) -> LiveReviewRun:
        headers = {
            "Authorization": f"Bearer {self.access_token}",
            "Idempotency-Key": idempotency_key or f"evaluation-{uuid4()}",
        }
        async with httpx.AsyncClient(timeout=min(self.timeout_seconds, 30.0)) as client:
            response = await client.post(
                f"{self.base_url}/api/v1/revisions/{revision_id}/review-runs",
                headers=headers,
            )
            response.raise_for_status()
            run = LiveReviewRun.model_validate(response.json())
            deadline = monotonic() + self.timeout_seconds
            while run.status not in {"COMPLETED", "FAILED", "CANCELLED"}:
                if monotonic() >= deadline:
                    raise TimeoutError(f"Review run {run.id} did not reach a terminal state")
                await asyncio.sleep(0.5)
                response = await client.get(
                    f"{self.base_url}/api/v1/review-runs/{run.id}",
                    headers={"Authorization": f"Bearer {self.access_token}"},
                )
                response.raise_for_status()
                run = LiveReviewRun.model_validate(response.json())
            return run
