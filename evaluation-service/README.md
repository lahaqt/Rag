# Authoring Coach Evaluation Service

This tool evaluates Authoring Coach behavior only. It contains no generic chat client or generic retrieval dataset entry point.

```bash
python -m pip install -e ".[test]"
authoring-eval live --revision-id REVISION_ID
authoring-eval dataset --dataset datasets/sample_authoring_eval.jsonl --output runs/latest.json
authoring-eval retrieval-ablation --dataset datasets/sample_retrieval_ablation.jsonl --output runs/retrieval.json
python -m pytest
```

`live` reads a student token from `AUTHORING_EVAL_ACCESS_TOKEN`, creates an asynchronous review run, waits for a terminal state, and returns the evidence and review payload. `dataset` measures Rubric coverage, citation validity, evidence utilization, draft change, score change, and optional human ratings. Add `--ragas` for optional evidence-grounding metrics.

`retrieval-ablation` compares recorded candidate rankings from retrieval variants such as hybrid RRF and Multi-Query/HyDE plus Cross-Encoder reranking. It reports Recall@20, NDCG@6, and authoritative Rubric evidence coverage@6 with stable, reviewable formulas. The sample dataset demonstrates the schema only; it is not a product performance claim.
