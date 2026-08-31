from __future__ import annotations

import json
import math
from collections import defaultdict
from pathlib import Path
from typing import Any


def evaluate_retrieval_ablation(dataset_path: str | Path) -> dict[str, Any]:
    path = Path(dataset_path)
    cases = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    results: list[dict[str, Any]] = []
    aggregates: dict[str, list[dict[str, float]]] = defaultdict(list)

    for case in cases:
        relevant_ids = set(case.get("relevantEvidenceIds", []))
        relevance = {str(key): float(value) for key, value in case.get("relevanceJudgments", {}).items()}
        rubric_evidence = {
            str(key): set(value) for key, value in case.get("authoritativeRubricEvidence", {}).items()
        }
        variants: dict[str, dict[str, float]] = {}
        for variant, ranked_items in case.get("runs", {}).items():
            metrics = _metrics(ranked_items, relevant_ids, relevance, rubric_evidence)
            variants[str(variant)] = metrics
            aggregates[str(variant)].append(metrics)
        results.append({"id": case["id"], "variants": variants})

    summary = {
        variant: {
            "case_count": len(values),
            **{
                metric: round(sum(item[metric] for item in values) / len(values), 6)
                for metric in ("recall_at_20", "ndcg_at_6", "authoritative_rubric_coverage_at_6")
            },
        }
        for variant, values in aggregates.items()
        if values
    }
    return {"datasetPath": str(path), "cases": results, "summary": summary}


def _metrics(
    ranked_items: list[dict[str, Any]],
    relevant_ids: set[str],
    relevance: dict[str, float],
    rubric_evidence: dict[str, set[str]],
) -> dict[str, float]:
    top_20_ids = [str(item["id"]) for item in ranked_items[:20]]
    recall = len(relevant_ids.intersection(top_20_ids)) / len(relevant_ids) if relevant_ids else 1.0

    gains = [relevance.get(str(item["id"]), 0.0) for item in ranked_items[:6]]
    ideal = sorted(relevance.values(), reverse=True)[:6]
    ideal_dcg = _dcg(ideal)
    ndcg = _dcg(gains) / ideal_dcg if ideal_dcg else 1.0

    authoritative_ids = {
        str(item["id"])
        for item in ranked_items[:6]
        if str(item.get("authority", "")).upper() == "AUTHORITATIVE"
    }
    covered = sum(1 for accepted_ids in rubric_evidence.values() if authoritative_ids.intersection(accepted_ids))
    coverage = covered / len(rubric_evidence) if rubric_evidence else 1.0
    return {
        "recall_at_20": round(recall, 6),
        "ndcg_at_6": round(ndcg, 6),
        "authoritative_rubric_coverage_at_6": round(coverage, 6),
    }


def _dcg(gains: list[float]) -> float:
    return sum((2**gain - 1) / math.log2(rank + 2) for rank, gain in enumerate(gains))


def write_retrieval_ablation(run: dict[str, Any], output_path: str | Path) -> None:
    Path(output_path).write_text(json.dumps(run, ensure_ascii=False, indent=2), encoding="utf-8")
