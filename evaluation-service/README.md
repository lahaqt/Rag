# Authoring Coach Evaluation Service

This tool evaluates Authoring Coach behavior only. It contains no generic chat client or generic retrieval dataset entry point.

```bash
python -m pip install -e ".[test]"
authoring-eval live --revision-id REVISION_ID
authoring-eval dataset --dataset datasets/sample_authoring_eval.jsonl --output runs/latest.json
python -m pytest
```

`live` reads a student token from `AUTHORING_EVAL_ACCESS_TOKEN`, creates an asynchronous review run, waits for a terminal state, and returns the evidence and review payload. `dataset` measures Rubric coverage, citation validity, evidence utilization, draft change, score change, and optional human ratings. Add `--ragas` for optional evidence-grounding metrics.
