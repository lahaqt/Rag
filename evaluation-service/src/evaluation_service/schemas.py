from __future__ import annotations

from datetime import datetime, timezone
from typing import Any
from uuid import uuid4

from pydantic import BaseModel, Field


class EvalCase(BaseModel):
    id: str
    question: str
    knowledgeBaseId: str | None = None
    reference: str | None = None
    expectedChunkIds: list[str] = Field(default_factory=list)
    tags: list[str] = Field(default_factory=list)
    difficulty: str | None = None
    source: dict[str, Any] = Field(default_factory=dict)
    options: dict[str, Any] = Field(default_factory=dict)


class RetrievedContext(BaseModel):
    index: int = 0
    knowledgeBaseId: str | None = None
    documentId: str | None = None
    chunkId: str | None = None
    chunkIndex: int | None = None
    documentName: str | None = None
    score: float | None = None
    content: str = ""


class AgentRunOutput(BaseModel):
    answer: str = ""
    traceId: str = ""
    intent: str = ""
    route: str = ""
    rewrittenQuery: str = ""
    retrievalQueries: list[str] = Field(default_factory=list)
    toolName: str = ""
    finishReason: str = ""
    contexts: list[RetrievedContext] = Field(default_factory=list)
    raw: dict[str, Any] = Field(default_factory=dict)


class CaseMetrics(BaseModel):
    context_count: int
    average_context_score: float | None = None
    expected_context_recall: float | None = None
    hit_at_k: bool | None = None
    mrr: float | None = None
    latency_ms: int


class EvalCaseResult(BaseModel):
    case: EvalCase
    output: AgentRunOutput
    metrics: CaseMetrics
    error: str | None = None


class EvaluationRun(BaseModel):
    runId: str = Field(default_factory=lambda: str(uuid4()))
    createdAt: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    agentBaseUrl: str
    datasetPath: str | None = None
    ragasEnabled: bool = False
    cases: list[EvalCaseResult] = Field(default_factory=list)
    summary: dict[str, Any] = Field(default_factory=dict)
    ragas: dict[str, Any] | None = None


class RunEvaluationRequest(BaseModel):
    datasetPath: str
    agentBaseUrl: str | None = None
    ragas: bool = False
    outputPath: str | None = None
