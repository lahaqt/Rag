from __future__ import annotations

import os
from pathlib import Path

from dotenv import load_dotenv
from pydantic import BaseModel, Field


class EvaluationSettings(BaseModel):
    agent_base_url: str = Field(default="http://127.0.0.1:28083")
    timeout_seconds: float = Field(default=120.0)
    default_top_k: int = Field(default=6)
    run_dir: Path = Field(default=Path("runs"))
    api_key: str = Field(default="")
    max_cases_per_run: int = Field(default=100, ge=1, le=1_000)
    max_concurrent_runs: int = Field(default=1, ge=1, le=8)
    max_stored_runs: int = Field(default=100, ge=1, le=10_000)
    allow_agent_url_override: bool = Field(default=False)
    allowed_agent_hosts: set[str] = Field(default_factory=set)


def load_settings() -> EvaluationSettings:
    load_dotenv()
    return EvaluationSettings(
        agent_base_url=os.getenv("AGENT_BASE_URL", "http://127.0.0.1:28083").rstrip("/"),
        timeout_seconds=float(os.getenv("EVAL_TIMEOUT_SECONDS", "120")),
        default_top_k=int(os.getenv("EVAL_DEFAULT_TOP_K", "6")),
        run_dir=Path(os.getenv("EVAL_RUN_DIR", "runs")),
        api_key=os.getenv("EVAL_API_KEY", ""),
        max_cases_per_run=int(os.getenv("EVAL_MAX_CASES_PER_RUN", "100")),
        max_concurrent_runs=int(os.getenv("EVAL_MAX_CONCURRENT_RUNS", "1")),
        max_stored_runs=int(os.getenv("EVAL_MAX_STORED_RUNS", "100")),
        allow_agent_url_override=os.getenv("EVAL_ALLOW_AGENT_URL_OVERRIDE", "false").lower() == "true",
        allowed_agent_hosts={
            host.strip().lower()
            for host in os.getenv("EVAL_ALLOWED_AGENT_HOSTS", "").split(",")
            if host.strip()
        },
    )
