# RAG Evaluation Service

Standalone evaluation module for the production `/api/chat` RAG path. It is a
black-box consumer of `agent-service`, not a runtime dependency of any Java
service, so evaluation failures must never block a user-facing chat request.

It calls `agent-service`, captures `answer`, `citations`, `retrievalHits`, and `traceId`, then computes deterministic retrieval diagnostics. RAGAS metrics are optional and run only when explicitly enabled with the Python RAGAS dependencies and evaluator LLM configuration.

## Install

```bash
cd evaluation-service
python -m venv .venv
.venv\Scripts\activate
python -m pip install -e ".[test]"
```

Install RAGAS support when you are ready to run LLM-as-judge metrics:

```bash
python -m pip install -e ".[ragas]"
```

## Run CLI

```bash
rag-eval run --dataset datasets/sample_rag_eval.jsonl --output runs/latest.json
```

Enable RAGAS:

```bash
rag-eval run --dataset datasets/sample_rag_eval.jsonl --output runs/latest.json --ragas
```

Without activating the shell, call the venv entry point directly:

```powershell
.\.venv\Scripts\rag-eval run --dataset datasets/sample_rag_eval.jsonl --output runs/latest.json --ragas
```

Default agent endpoint:

```txt
http://127.0.0.1:28083
```

Override it with `--agent-base-url` or `AGENT_BASE_URL`.

## Run API

```bash
uvicorn evaluation_service.api:app --host 127.0.0.1 --port 28084 --reload
```

```txt
GET  /api/evaluations/health
POST /api/evaluations/runs
GET  /api/evaluations/runs/{run_id}
```

## Dataset Format

Each line is one test case:

```json
{"id":"case-001","question":"退货政策是什么？","knowledgeBaseId":"default","reference":"支持 7 天无理由退货，特殊商品除外。","expectedChunkIds":["chunk-return-policy"],"tags":["policy"]}
```

Important fields:

- `question`: user query sent to `/api/chat`.
- `knowledgeBaseId`: optional target knowledge base.
- `reference`: expected answer, required for answer correctness style metrics.
- `expectedChunkIds`: optional chunk ids for deterministic recall and MRR.
- `options`: optional chat options passed through to `agent-service`.

## Metrics

Local deterministic metrics:

- `context_count`
- `average_context_score`
- `expected_context_recall`
- `hit_at_k`
- `mrr`
- `latency_ms`

Optional RAGAS metrics:

- `Faithfulness`
- `ContextPrecision`
- `ContextRecall`

RAGAS needs evaluator model access. This project runs the LLM-only RAGAS metrics by default so it can reuse the existing Ark chat model without configuring a separate embedding model. `AnswerRelevancy` is intentionally not enabled by default because it requires an embeddings evaluator in RAGAS 0.2.x.

This module can reuse the same Ark key as the Java services. The evaluator LLM config is resolved in this order:

```txt
api key:  RAGAS_EVALUATOR_API_KEY -> OPENAI_API_KEY -> ARK_API_KEY
base url: RAGAS_EVALUATOR_BASE_URL -> OPENAI_API_BASE -> OPENAI_BASE_URL -> ARK_BASE_URL -> https://ark.cn-beijing.volces.com/api/plan/v3
model:    RAGAS_EVALUATOR_MODEL -> ARK_EVALUATOR_MODEL -> ARK_MODEL -> ark-code-latest
```

For the default local setup, `ARK_API_KEY` is enough:

```powershell
$env:ARK_API_KEY="your ark key"
.\.venv\Scripts\rag-eval run --dataset datasets/sample_rag_eval.jsonl --output runs/latest.json --ragas
```
