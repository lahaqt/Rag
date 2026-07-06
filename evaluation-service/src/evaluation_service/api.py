from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI, HTTPException

from .config import load_settings
from .evaluator import run_dataset, write_run
from .schemas import EvaluationRun, RunEvaluationRequest

settings = load_settings()
app = FastAPI(title="RAG Evaluation Service", version="0.1.0")
_runs: dict[str, EvaluationRun] = {}


@app.get("/api/evaluations/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.post("/api/evaluations/runs")
async def create_run(request: RunEvaluationRequest) -> EvaluationRun:
    agent_base_url = (request.agentBaseUrl or settings.agent_base_url).rstrip("/")
    try:
        run = await run_dataset(
            request.datasetPath,
            agent_base_url=agent_base_url,
            timeout_seconds=settings.timeout_seconds,
            run_ragas=request.ragas,
        )
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    _runs[run.runId] = run
    output_path = request.outputPath
    if output_path:
        write_run(run, output_path)
    else:
        write_run(run, settings.run_dir / f"{run.runId}.json")
    return run


@app.get("/api/evaluations/runs/{run_id}")
def get_run(run_id: str) -> EvaluationRun:
    if run_id in _runs:
        return _runs[run_id]

    stored_path = Path(settings.run_dir) / f"{run_id}.json"
    if stored_path.exists():
        return EvaluationRun.model_validate_json(stored_path.read_text(encoding="utf-8"))
    raise HTTPException(status_code=404, detail=f"Evaluation run not found: {run_id}")

