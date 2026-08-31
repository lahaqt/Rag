from __future__ import annotations

import json
from pathlib import Path

from evaluation_service.retrieval_ablation import evaluate_retrieval_ablation


def test_compares_recall_ranking_and_authoritative_rubric_coverage(tmp_path: Path) -> None:
    dataset = tmp_path / "retrieval.jsonl"
    case = {
        "id": "beam-1",
        "relevantEvidenceIds": ["stress", "axis", "moment"],
        "relevanceJudgments": {"stress": 3, "axis": 2, "moment": 2, "noise": 0},
        "authoritativeRubricEvidence": {
            "technical_accuracy": ["stress"],
            "conceptual_completeness": ["axis", "moment"],
        },
        "runs": {
            "hybrid_rrf": [
                {"id": "noise", "authority": "SUPPLEMENTAL"},
                {"id": "stress", "authority": "AUTHORITATIVE"},
            ],
            "multiquery_hyde_rerank": [
                {"id": "stress", "authority": "AUTHORITATIVE"},
                {"id": "axis", "authority": "AUTHORITATIVE"},
                {"id": "moment", "authority": "SUPPLEMENTAL"},
            ],
        },
    }
    dataset.write_text(json.dumps(case) + "\n", encoding="utf-8")

    run = evaluate_retrieval_ablation(dataset)

    baseline = run["summary"]["hybrid_rrf"]
    enhanced = run["summary"]["multiquery_hyde_rerank"]
    assert baseline["recall_at_20"] == round(1 / 3, 6)
    assert enhanced["recall_at_20"] == 1.0
    assert enhanced["ndcg_at_6"] > baseline["ndcg_at_6"]
    assert baseline["authoritative_rubric_coverage_at_6"] == 0.5
    assert enhanced["authoritative_rubric_coverage_at_6"] == 1.0
