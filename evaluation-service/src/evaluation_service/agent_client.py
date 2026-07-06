from __future__ import annotations

import time
from typing import Any

import httpx

from .schemas import AgentRunOutput, EvalCase, RetrievedContext


class AgentClient:
    def __init__(self, base_url: str, timeout_seconds: float = 120.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds

    async def run_case(self, case: EvalCase) -> tuple[AgentRunOutput, int]:
        payload: dict[str, Any] = {
            "query": case.question,
            "knowledgeBaseId": case.knowledgeBaseId,
            "options": case.options or None,
        }
        payload = {key: value for key, value in payload.items() if value is not None}
        started = time.perf_counter()
        async with httpx.AsyncClient(timeout=self.timeout_seconds) as client:
            response = await client.post(f"{self.base_url}/api/chat", json=payload)
            response.raise_for_status()
            data = response.json()
        latency_ms = int((time.perf_counter() - started) * 1000)
        return parse_agent_response(data), latency_ms


def parse_agent_response(data: dict[str, Any]) -> AgentRunOutput:
    contexts = [_context_from_item(item) for item in _context_items(data)]
    return AgentRunOutput(
        answer=str(data.get("answer") or ""),
        traceId=str(data.get("traceId") or ""),
        intent=str(data.get("intent") or ""),
        route=str(data.get("route") or ""),
        rewrittenQuery=str(data.get("rewrittenQuery") or ""),
        retrievalQueries=[str(item) for item in data.get("retrievalQueries") or []],
        toolName=str(data.get("toolName") or ""),
        finishReason=str(data.get("finishReason") or ""),
        contexts=contexts,
        raw=data,
    )


def _context_items(data: dict[str, Any]) -> list[dict[str, Any]]:
    retrieval_hits = data.get("retrievalHits") or []
    citations = data.get("citations") or []
    items = retrieval_hits if retrieval_hits else citations
    return [item for item in items if isinstance(item, dict)]


def _context_from_item(item: dict[str, Any]) -> RetrievedContext:
    content = item.get("content") or item.get("excerpt") or ""
    return RetrievedContext(
        index=int(item.get("index") or 0),
        knowledgeBaseId=item.get("knowledgeBaseId"),
        documentId=item.get("documentId"),
        chunkId=item.get("chunkId"),
        chunkIndex=item.get("chunkIndex"),
        documentName=item.get("documentName"),
        score=_float_or_none(item.get("score")),
        content=str(content),
    )


def _float_or_none(value: Any) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None

