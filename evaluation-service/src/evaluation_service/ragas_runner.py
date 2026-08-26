from __future__ import annotations

import os
from typing import Any

import httpx

from .schemas import AuthoringCaseResult


class RagasUnavailableError(RuntimeError):
    pass


def evaluate_authoring_with_ragas(results: list[AuthoringCaseResult]) -> dict[str, Any]:
    rows = []
    for result in results:
        case = result.case
        if not case.referenceFeedback:
            continue
        evidence = [str(item.get("excerpt") or item.get("content") or "") for item in case.revisedReview.evidence]
        rows.append({
            "user_input": json_text(case.revisedDraft),
            "response": case.revisedReview.summary + "\n" + json_text(case.revisedReview.dimensions),
            "retrieved_contexts": [item for item in evidence if item],
            "reference": case.referenceFeedback,
        })
    return _evaluate_rows(rows)


def _evaluate_rows(rows: list[dict[str, Any]]) -> dict[str, Any]:
    if not rows:
        return {"scores": [], "summary": {}}

    try:
        from datasets import Dataset
        from langchain_openai import ChatOpenAI
        from ragas import evaluate
        from ragas.llms import LangchainLLMWrapper
        from ragas.metrics import ContextPrecision, ContextRecall, Faithfulness
    except ImportError as exc:
        raise RagasUnavailableError(
            "RAGAS dependencies are not installed. Run: pip install -e \".[ragas]\""
        ) from exc

    dataset = Dataset.from_list(rows)
    evaluator_llm = LangchainLLMWrapper(_build_evaluator_llm(ChatOpenAI))
    metrics = [
        Faithfulness(llm=evaluator_llm),
        ContextPrecision(llm=evaluator_llm),
        ContextRecall(llm=evaluator_llm),
    ]
    result = evaluate(dataset=dataset, metrics=metrics)
    dataframe = result.to_pandas()
    return {
        "scores": dataframe.to_dict(orient="records"),
        "summary": _numeric_summary(dataframe.to_dict(orient="records")),
    }


def json_text(value: Any) -> str:
    import json

    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def _build_evaluator_llm(chat_openai_class):
    api_key = os.getenv("RAGAS_EVALUATOR_API_KEY") or os.getenv("OPENAI_API_KEY") or os.getenv("ARK_API_KEY")
    base_url = (
        os.getenv("RAGAS_EVALUATOR_BASE_URL")
        or os.getenv("OPENAI_API_BASE")
        or os.getenv("OPENAI_BASE_URL")
        or os.getenv("ARK_BASE_URL")
        or "https://ark.cn-beijing.volces.com/api/plan/v3"
    )
    model = os.getenv("RAGAS_EVALUATOR_MODEL") or os.getenv("ARK_EVALUATOR_MODEL") or os.getenv("ARK_MODEL") or "ark-code-latest"
    timeout = float(os.getenv("RAGAS_EVALUATOR_TIMEOUT_SECONDS", "60"))
    if not api_key:
        raise RagasUnavailableError(
            "RAGAS evaluator LLM is not configured. Set ARK_API_KEY or RAGAS_EVALUATOR_API_KEY."
        )
    return chat_openai_class(
        model=model,
        api_key=api_key,
        base_url=base_url,
        temperature=0,
        timeout=timeout,
        max_retries=1,
        http_client=httpx.Client(trust_env=False, timeout=timeout),
        http_async_client=httpx.AsyncClient(trust_env=False, timeout=timeout),
    )


def _numeric_summary(rows: list[dict[str, Any]]) -> dict[str, float]:
    summary: dict[str, float] = {}
    if not rows:
        return summary
    keys = sorted({key for row in rows for key, value in row.items() if isinstance(value, int | float)})
    for key in keys:
        values = [float(row[key]) for row in rows if isinstance(row.get(key), int | float)]
        if values:
            summary[key] = round(sum(values) / len(values), 6)
    return summary
