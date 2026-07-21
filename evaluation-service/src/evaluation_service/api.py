from __future__ import annotations

import asyncio
import hmac
from collections import OrderedDict
from pathlib import Path
from typing import Annotated
from urllib.parse import urlparse

from fastapi import Depends, FastAPI, Header, HTTPException, status

from .config import load_settings
from .evaluator import run_dataset, write_run
from .schemas import EvaluationRun, RunEvaluationRequest

settings = load_settings()
app = FastAPI(title="RAG Evaluation Service", version="0.1.0")
_runs: OrderedDict[str, EvaluationRun] = OrderedDict()
_run_semaphore = asyncio.Semaphore(settings.max_concurrent_runs)


@app.get("/api/evaluations/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


def require_api_key(x_eval_api_key: Annotated[str | None, Header()] = None) -> None:
    configured_key = settings.api_key
    if not configured_key or not x_eval_api_key or not hmac.compare_digest(x_eval_api_key, configured_key):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Evaluation API key is required")


@app.post("/api/evaluations/runs")
async def create_run(request: RunEvaluationRequest, _: None = Depends(require_api_key)) -> EvaluationRun:
    try:
        await asyncio.wait_for(_run_semaphore.acquire(), timeout=0.05)
    except TimeoutError as exc:
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail="Evaluation capacity is exhausted") from exc
    try:
        agent_base_url = resolve_agent_base_url(request.agentBaseUrl)
        try:
            run = await run_dataset(
                request.datasetPath,
                agent_base_url=agent_base_url,
                timeout_seconds=settings.timeout_seconds,
                run_ragas=request.ragas,
                max_cases=settings.max_cases_per_run,
            )
        except Exception as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        _runs[run.runId] = run
        _runs.move_to_end(run.runId)
        while len(_runs) > settings.max_stored_runs:
            _runs.popitem(last=False)
        output_path = request.outputPath
        if output_path:
            write_run(run, output_path)
        else:
            write_run(run, settings.run_dir / f"{run.runId}.json")
        return run
    finally:
        _run_semaphore.release()


def resolve_agent_base_url(requested_url: str | None) -> str:
    if not requested_url:
        return settings.agent_base_url.rstrip("/")
    if not settings.allow_agent_url_override:
        raise HTTPException(status_code=400, detail="Per-run agentBaseUrl overrides are disabled")
    parsed = urlparse(requested_url)
    host = (parsed.hostname or "").lower()
    if parsed.scheme not in {"http", "https"} or not host or host not in settings.allowed_agent_hosts:
        raise HTTPException(status_code=400, detail="agentBaseUrl must use an explicitly allowed http(s) host")
    return requested_url.rstrip("/")


@app.get("/api/evaluations/runs/{run_id}")
def get_run(run_id: str, _: None = Depends(require_api_key)) -> EvaluationRun:
    if run_id in _runs:
        return _runs[run_id]

    stored_path = Path(settings.run_dir) / f"{run_id}.json"
    if stored_path.exists():
        return EvaluationRun.model_validate_json(stored_path.read_text(encoding="utf-8"))
    raise HTTPException(status_code=404, detail=f"Evaluation run not found: {run_id}")
