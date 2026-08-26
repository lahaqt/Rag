from __future__ import annotations

from datetime import datetime, timezone
from typing import Any
from uuid import uuid4

from pydantic import BaseModel, Field


class LiveReviewRun(BaseModel):
    id: str
    revisionId: str
    status: str
    currentPhase: str = ""
    failureReason: str = ""
    review: dict[str, Any] | None = None


class RunAuthoringEvaluationRequest(BaseModel):
    datasetPath: str
    ragas: bool = False
    outputPath: str | None = None


class AuthoringReviewSnapshot(BaseModel):
    status: str = ""
    overallScore: float | None = None
    dimensions: list[dict[str, Any]] = Field(default_factory=list)
    evidence: list[dict[str, Any]] = Field(default_factory=list)
    summary: str = ""


class AuthoringHumanRatings(BaseModel):
    pertinence: int | None = Field(default=None, ge=1, le=5)
    actionability: int | None = Field(default=None, ge=1, le=5)
    educationalValue: int | None = Field(default=None, ge=1, le=5)


class AuthoringEvalCase(BaseModel):
    id: str
    artifactType: str
    originalDraft: Any
    revisedDraft: Any
    originalReview: AuthoringReviewSnapshot
    revisedReview: AuthoringReviewSnapshot
    referenceFeedback: str | None = None
    humanRatings: AuthoringHumanRatings = Field(default_factory=AuthoringHumanRatings)
    tags: list[str] = Field(default_factory=list)


class AuthoringCaseMetrics(BaseModel):
    draft_changed: bool
    review_completed: bool
    original_score: float | None = None
    revised_score: float | None = None
    score_delta: float | None = None
    rubric_coverage: float
    citation_validity: float | None = None
    evidence_utilization: float | None = None
    pertinence: int | None = None
    actionability: int | None = None
    educational_value: int | None = None


class AuthoringCaseResult(BaseModel):
    case: AuthoringEvalCase
    metrics: AuthoringCaseMetrics


class AuthoringEvaluationRun(BaseModel):
    runId: str = Field(default_factory=lambda: str(uuid4()))
    createdAt: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    datasetPath: str
    ragasEnabled: bool = False
    cases: list[AuthoringCaseResult] = Field(default_factory=list)
    summary: dict[str, Any] = Field(default_factory=dict)
    ragas: dict[str, Any] | None = None
