from __future__ import annotations

from statistics import mean

from .schemas import AgentRunOutput, CaseMetrics, EvalCase


def calculate_case_metrics(case: EvalCase, output: AgentRunOutput, latency_ms: int) -> CaseMetrics:
    context_scores = [context.score for context in output.contexts if context.score is not None]
    retrieved_ids = [context.chunkId for context in output.contexts if context.chunkId]
    expected_ids = case.expectedChunkIds

    expected_recall: float | None = None
    hit_at_k: bool | None = None
    mrr: float | None = None
    if expected_ids:
        expected = set(expected_ids)
        hits = [chunk_id for chunk_id in retrieved_ids if chunk_id in expected]
        expected_recall = len(set(hits)) / len(expected)
        hit_at_k = bool(hits)
        mrr = _mrr(retrieved_ids, expected)

    return CaseMetrics(
        context_count=len(output.contexts),
        average_context_score=round(mean(context_scores), 6) if context_scores else None,
        expected_context_recall=round(expected_recall, 6) if expected_recall is not None else None,
        hit_at_k=hit_at_k,
        mrr=round(mrr, 6) if mrr is not None else None,
        latency_ms=latency_ms,
    )


def summarize_case_metrics(results: list) -> dict:
    successful = [result for result in results if result.error is None]
    metrics = [result.metrics for result in successful]

    def avg(values: list[float | int | None]) -> float | None:
        present = [float(value) for value in values if value is not None]
        return round(mean(present), 6) if present else None

    hit_values = [metric.hit_at_k for metric in metrics if metric.hit_at_k is not None]
    return {
        "caseCount": len(results),
        "successCount": len(successful),
        "failureCount": len(results) - len(successful),
        "averageContextCount": avg([metric.context_count for metric in metrics]),
        "averageContextScore": avg([metric.average_context_score for metric in metrics]),
        "averageExpectedContextRecall": avg([metric.expected_context_recall for metric in metrics]),
        "hitRateAtK": round(sum(1 for value in hit_values if value) / len(hit_values), 6) if hit_values else None,
        "averageMrr": avg([metric.mrr for metric in metrics]),
        "averageLatencyMs": avg([metric.latency_ms for metric in metrics]),
    }


def _mrr(retrieved_ids: list[str], expected_ids: set[str]) -> float:
    for index, chunk_id in enumerate(retrieved_ids, start=1):
        if chunk_id in expected_ids:
            return 1.0 / index
    return 0.0

