from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from .ragas_runner import evaluate_authoring_with_ragas
from .schemas import (
    AuthoringCaseMetrics,
    AuthoringCaseResult,
    AuthoringEvalCase,
    AuthoringEvaluationRun,
)

COMMON_DIMENSIONS = {
    "technical_accuracy",
    "conceptual_completeness",
    "learning_outcome_alignment",
    "semantic_clarity",
}
MCQ_DIMENSIONS = {"mcq_answer_correctness", "distractor_quality", "difficulty_alignment"}


def load_authoring_dataset(path: str | Path) -> list[AuthoringEvalCase]:
    dataset_path = Path(path)
    if not dataset_path.exists():
        raise FileNotFoundError(f"Dataset not found: {dataset_path}")
    cases: list[AuthoringEvalCase] = []
    with dataset_path.open("r", encoding="utf-8-sig") as handle:
        for line_number, line in enumerate(handle, start=1):
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            try:
                cases.append(AuthoringEvalCase.model_validate(json.loads(stripped)))
            except (json.JSONDecodeError, ValueError) as exc:
                raise ValueError(f"Invalid authoring case at {dataset_path}:{line_number}: {exc}") from exc
    if not cases:
        raise ValueError(f"Dataset is empty: {dataset_path}")
    return cases


def evaluate_authoring_dataset(path: str | Path, run_ragas: bool = False) -> AuthoringEvaluationRun:
    cases = load_authoring_dataset(path)
    results = [AuthoringCaseResult(case=case, metrics=_metrics(case)) for case in cases]
    run = AuthoringEvaluationRun(
        datasetPath=str(path),
        ragasEnabled=run_ragas,
        cases=results,
        summary=_summary(results),
    )
    if run_ragas:
        run.ragas = evaluate_authoring_with_ragas(results)
    return run


def write_authoring_run(run: AuthoringEvaluationRun, output_path: str | Path) -> Path:
    path = Path(output_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(run.model_dump(mode="json"), ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def _metrics(case: AuthoringEvalCase) -> AuthoringCaseMetrics:
    required = set(COMMON_DIMENSIONS)
    if case.artifactType == "MULTIPLE_CHOICE_QUESTION":
        required.update(MCQ_DIMENSIONS)
    dimensions = case.revisedReview.dimensions
    keys = {str(item.get("key", "")).strip().lower() for item in dimensions}
    references = [ref for item in dimensions for ref in item.get("evidenceRefs", []) if isinstance(ref, int)]
    evidence_count = len(case.revisedReview.evidence)
    valid_references = [ref for ref in references if 1 <= ref <= evidence_count]
    cited_evidence = len(set(valid_references))
    original_score = case.originalReview.overallScore
    revised_score = case.revisedReview.overallScore
    return AuthoringCaseMetrics(
        draft_changed=case.originalDraft != case.revisedDraft,
        original_score=original_score,
        revised_score=revised_score,
        score_delta=None if original_score is None or revised_score is None else round(revised_score - original_score, 6),
        rubric_coverage=round(len(required & keys) / len(required), 6),
        citation_validity=None if not references else round(len(valid_references) / len(references), 6),
        evidence_utilization=None if evidence_count == 0 else round(cited_evidence / evidence_count, 6),
        pertinence=case.humanRatings.pertinence,
        actionability=case.humanRatings.actionability,
        educational_value=case.humanRatings.educationalValue,
    )


def _summary(results: list[AuthoringCaseResult]) -> dict[str, Any]:
    fields = (
        "score_delta", "rubric_coverage", "citation_validity", "evidence_utilization",
        "pertinence", "actionability", "educational_value",
    )
    summary: dict[str, Any] = {
        "case_count": len(results),
        "draft_change_rate": round(sum(result.metrics.draft_changed for result in results) / len(results), 6),
    }
    for field in fields:
        values = [getattr(result.metrics, field) for result in results if getattr(result.metrics, field) is not None]
        summary[f"average_{field}"] = None if not values else round(sum(values) / len(values), 6)
    return summary
