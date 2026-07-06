from __future__ import annotations

import asyncio
import json
from pathlib import Path

from .agent_client import AgentClient
from .dataset import load_jsonl_dataset
from .metrics import calculate_case_metrics, summarize_case_metrics
from .ragas_runner import evaluate_with_ragas
from .schemas import EvalCase, EvalCaseResult, EvaluationRun


class EvaluationRunner:
    def __init__(self, agent_client: AgentClient) -> None:
        self.agent_client = agent_client

    async def run_cases(
        self,
        cases: list[EvalCase],
        dataset_path: str | None = None,
        run_ragas: bool = False,
    ) -> EvaluationRun:
        case_results: list[EvalCaseResult] = []
        for case in cases:
            case_results.append(await self._run_single_case(case))

        run = EvaluationRun(
            agentBaseUrl=self.agent_client.base_url,
            datasetPath=dataset_path,
            ragasEnabled=run_ragas,
            cases=case_results,
            summary=summarize_case_metrics(case_results),
        )
        if run_ragas:
            run.ragas = evaluate_with_ragas(case_results)
        return run

    async def _run_single_case(self, case: EvalCase) -> EvalCaseResult:
        try:
            output, latency_ms = await self.agent_client.run_case(case)
            metrics = calculate_case_metrics(case, output, latency_ms)
            return EvalCaseResult(case=case, output=output, metrics=metrics)
        except Exception as exc:
            empty_output = self._empty_output()
            metrics = calculate_case_metrics(case, empty_output, 0)
            return EvalCaseResult(case=case, output=empty_output, metrics=metrics, error=str(exc))

    @staticmethod
    def _empty_output():
        from .schemas import AgentRunOutput

        return AgentRunOutput()


async def run_dataset(
    dataset_path: str | Path,
    agent_base_url: str,
    timeout_seconds: float = 120.0,
    run_ragas: bool = False,
) -> EvaluationRun:
    cases = load_jsonl_dataset(dataset_path)
    runner = EvaluationRunner(AgentClient(agent_base_url, timeout_seconds=timeout_seconds))
    return await runner.run_cases(cases, dataset_path=str(dataset_path), run_ragas=run_ragas)


def run_dataset_sync(
    dataset_path: str | Path,
    agent_base_url: str,
    timeout_seconds: float = 120.0,
    run_ragas: bool = False,
) -> EvaluationRun:
    return asyncio.run(run_dataset(dataset_path, agent_base_url, timeout_seconds, run_ragas))


def write_run(run: EvaluationRun, output_path: str | Path) -> Path:
    path = Path(output_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(run.model_dump(mode="json"), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return path

