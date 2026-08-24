from __future__ import annotations

from pathlib import Path

from evaluation_service.authoring_evaluator import evaluate_authoring_dataset


def test_evaluates_revision_gain_rubric_evidence_and_human_ratings(tmp_path: Path) -> None:
    dataset = tmp_path / "authoring.jsonl"
    dataset.write_text(
        '{"id":"case-1","artifactType":"TECHNICAL_INTERPRETATION",'
        '"originalDraft":{"body":"Pressure changes."},"revisedDraft":{"body":"Pressure and velocity trade off."},'
        '"originalReview":{"overallScore":1.5},'
        '"revisedReview":{"status":"COMPLETED","overallScore":3.0,"summary":"Stronger explanation.",'
        '"evidence":[{"index":1,"excerpt":"Bernoulli evidence"},{"index":2,"excerpt":"Continuity evidence"}],'
        '"dimensions":[{"key":"technical_accuracy","evidenceRefs":[1,3]},'
        '{"key":"conceptual_completeness","evidenceRefs":[2]},'
        '{"key":"learning_outcome_alignment","evidenceRefs":[]},'
        '{"key":"semantic_clarity","evidenceRefs":[]}]},'
        '"referenceFeedback":"The revision should connect pressure and velocity.",'
        '"humanRatings":{"pertinence":5,"actionability":4,"educationalValue":5}}\n',
        encoding="utf-8",
    )

    run = evaluate_authoring_dataset(dataset)

    metrics = run.cases[0].metrics
    assert metrics.draft_changed is True
    assert metrics.review_completed is True
    assert metrics.score_delta == 1.5
    assert metrics.rubric_coverage == 1.0
    assert metrics.citation_validity == round(2 / 3, 6)
    assert metrics.evidence_utilization == 1.0
    assert run.summary["average_pertinence"] == 5.0
    assert run.summary["review_completion_rate"] == 1.0


def test_mcq_requires_specialized_dimensions(tmp_path: Path) -> None:
    dataset = tmp_path / "mcq.jsonl"
    dataset.write_text(
        '{"id":"case-2","artifactType":"MULTIPLE_CHOICE_QUESTION",'
        '"originalDraft":{},"revisedDraft":{"stem":"Question"},'
        '"originalReview":{},"revisedReview":{"dimensions":['
        '{"key":"technical_accuracy"},{"key":"conceptual_completeness"},'
        '{"key":"learning_outcome_alignment"},{"key":"semantic_clarity"}]}}\n',
        encoding="utf-8",
    )

    run = evaluate_authoring_dataset(dataset)

    assert run.cases[0].metrics.rubric_coverage == round(4 / 7, 6)


def test_counts_completed_reviews_and_penalizes_missing_citations(tmp_path: Path) -> None:
    dataset = tmp_path / "completed.jsonl"
    dataset.write_text(
        '{"id":"case-3","artifactType":"TECHNICAL_INTERPRETATION",'
        '"originalDraft":{},"revisedDraft":{"body":"Revised"},'
        '"originalReview":{},"revisedReview":{"status":"COMPLETED",'
        '"evidence":[{"index":1,"excerpt":"Course evidence"}],'
        '"dimensions":[{"key":"technical_accuracy","evidenceRefs":[]}]}}\n',
        encoding="utf-8",
    )

    run = evaluate_authoring_dataset(dataset)

    assert run.cases[0].metrics.review_completed is True
    assert run.cases[0].metrics.citation_validity == 0.0
    assert run.summary["review_completion_rate"] == 1.0
