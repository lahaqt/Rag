from __future__ import annotations

from evaluation_service.agent_client import parse_agent_response
from evaluation_service.metrics import calculate_case_metrics
from evaluation_service.schemas import EvalCase


def test_parse_agent_response_prefers_retrieval_hits() -> None:
    output = parse_agent_response(
        {
            "answer": "退货政策答案",
            "traceId": "trace-1",
            "citations": [{"chunkId": "citation-only", "content": "citation"}],
            "retrievalHits": [
                {"index": 1, "chunkId": "chunk-return-policy", "content": "退货政策", "score": 0.9}
            ],
        }
    )

    assert output.answer == "退货政策答案"
    assert output.traceId == "trace-1"
    assert [context.chunkId for context in output.contexts] == ["chunk-return-policy"]


def test_calculate_expected_context_metrics() -> None:
    case = EvalCase(
        id="case-1",
        question="退货政策是什么？",
        expectedChunkIds=["chunk-return-policy", "chunk-extra"],
    )
    output = parse_agent_response(
        {
            "answer": "退货政策答案",
            "retrievalHits": [
                {"index": 1, "chunkId": "other", "content": "其他", "score": 0.2},
                {"index": 2, "chunkId": "chunk-return-policy", "content": "退货政策", "score": 0.9},
            ],
        }
    )

    metrics = calculate_case_metrics(case, output, latency_ms=123)

    assert metrics.context_count == 2
    assert metrics.expected_context_recall == 0.5
    assert metrics.hit_at_k is True
    assert metrics.mrr == 0.5
    assert metrics.latency_ms == 123

