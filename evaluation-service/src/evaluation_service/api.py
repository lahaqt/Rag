from __future__ import annotations

import asyncio
import hmac
from collections import OrderedDict
from pathlib import Path
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, status

from .authoring_evaluator import evaluate_authoring_dataset, write_authoring_run
from .config import load_settings
from .schemas import AuthoringEvaluationRun, RunAuthoringEvaluationRequest

settings = load_settings()
app = FastAPI(title="Authoring Coach Evaluation Service", version="1.0.0")
_runs: OrderedDict[str, AuthoringEvaluationRun] = OrderedDict()
_run_semaphore = asyncio.Semaphore(settings.max_concurrent_runs)


@app.get("/api/evaluations/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


def require_api_key(x_eval_api_key: Annotated[str | None, Header()] = None) -> None:
    if not settings.api_key or not x_eval_api_key or not hmac.compare_digest(x_eval_api_key, settings.api_key):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Evaluation API key is required")


@app.post("/api/evaluations/runs")
async def create_run(request: RunAuthoringEvaluationRequest, _: None = Depends(require_api_key)) -> AuthoringEvaluationRun:
    try:
        await asyncio.wait_for(_run_semaphore.acquire(), timeout=0.05)
    except TimeoutError as exc:
        raise HTTPException(status_code=429, detail="Evaluation capacity is exhausted") from exc
    try:
        try:
            run = await asyncio.to_thread(evaluate_authoring_dataset, request.datasetPath, request.ragas)
        except Exception as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        _runs[run.runId] = run
        while len(_runs) > settings.max_stored_runs:
            _runs.popitem(last=False)
        write_authoring_run(run, request.outputPath or settings.run_dir / f"{run.runId}.json")
        return run
    finally:
        _run_semaphore.release()


@app.get("/api/evaluations/runs/{run_id}")
def get_run(run_id: str, _: None = Depends(require_api_key)) -> AuthoringEvaluationRun:
    if run_id in _runs:
        return _runs[run_id]
    path = Path(settings.run_dir) / f"{run_id}.json"
    if path.exists():
        return AuthoringEvaluationRun.model_validate_json(path.read_text(encoding="utf-8"))
    raise HTTPException(status_code=404, detail=f"Evaluation run not found: {run_id}")
