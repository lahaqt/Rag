# RAG Agent Workspace

版本：`1.0.0`

这是一个本地可运行的 RAG Agent 多模块工作区，覆盖知识库文档管理、查询分析与改写、Agent 编排和前端交互。整体链路从前端问题输入开始，经由 Agent 服务完成意图分析、工具路由、知识库检索、Prompt 组装和答案生成。

## 模块结构

```txt
frontend
  -> agent-service
      -> query-analysis-service
      -> knowledge-service
      -> web_search / tools
      -> LLM Provider
```

- `frontend/`：React 19 + TypeScript + Vite 前端，提供聊天、知识库管理、文档上传、引用展示和检索参数控制。
- `agent-service/`：RAG Agent 编排服务，负责 `/api/chat` 和 `/api/chat/stream`，串联意图树消费、执行模式选择、工具调用、检索、答案生成和引用返回。
- `query-analysis-service/`：查询分析与改写服务，负责意图分类、路由建议、执行模式、工具能力约束、query rewrite 和多查询生成。
- `knowledge-service/`：知识库服务，负责文档解析、chunk、元数据存储、对象存储、向量索引、BM25/hybrid retrieval 和相关 API。
- `documents/`：示例业务知识文档。
- `knowledge-base/`：RAG 相关主题知识材料。

## 服务端口

```txt
frontend:              http://127.0.0.1:5173
knowledge-service:      http://127.0.0.1:28081
query-analysis-service: http://127.0.0.1:28082
agent-service:          http://127.0.0.1:28083

PostgreSQL + pgvector: localhost:25432
Redis Stream:          localhost:26380
RustFS S3 API:         http://localhost:29100
RustFS Console:        http://localhost:29101
```

## 本地启动

先启动 `knowledge-service` 依赖：

```bash
cd knowledge-service
docker compose up -d
```

再分别启动后端服务：

```bash
cd knowledge-service
mvn spring-boot:run
```

```bash
cd query-analysis-service
mvn spring-boot:run
```

```bash
cd agent-service
mvn spring-boot:run
```

启动前端：

```bash
cd frontend
npm install
npm run dev
```

访问：

```txt
http://127.0.0.1:5173
```

## 验证命令

```bash
cd knowledge-service
mvn test
```

```bash
cd query-analysis-service
mvn test
```

```bash
cd agent-service
mvn test
```

```bash
cd frontend
npm run lint
npm run build
```

## 配置说明

各服务配置文件位于：

```txt
knowledge-service/src/main/resources/application.yml
query-analysis-service/src/main/resources/application.yml
agent-service/src/main/resources/application.yml
frontend/vite.config.ts
```

不要提交本地密钥、运行日志、构建产物、`node_modules/`、`target/`、`dist/`、`knowledge-service/data/` 或 `knowledge-service/tmp/`。

## Agent 主链路

`query-analysis-service` 会优先使用 LLM JSON 分类器输出兼容旧字段的意图树：`requestType`、`route`、`executionMode`、`requiredCapabilities` 和 `clarificationQuestion`。LLM 未配置、调用失败或返回 JSON 不合法时，会自动回退到本地规则分类器。简单闲聊、澄清和系统指令识别走 `DIRECT`，由 `agent-service` 直接生成响应；知识库、联网搜索和 MCP 工具请求走 `SINGLE_TOOL`、`ITERATIVE_TOOL` 或后续 `PLANNED_TASK`，再进入 ReAct 工具循环。

`agent-service` 的 `ToolRegistry` 优先遵守 `requiredCapabilities`，例如 `rag_retrieval`、`web_search`、`mcp_tool`，再回退到关键词路由。这样业务知识问题不会因为出现“查一下”等泛化词而误走联网搜索。

## 可观测性

三个后端服务均接入 Micrometer Tracing + OpenTelemetry bridge，HTTP 响应会暴露 `X-Trace-Id` 和 `X-Span-Id`，服务间调用通过 W3C `traceparent` 传播上下文。默认 OTLP endpoint 为：

```txt
http://localhost:4318/v1/traces
```

可通过环境变量覆盖：

```txt
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT
OTEL_TRACES_SAMPLER_PROBABILITY
```

`agent-service` 还会将业务级 `AgentTrace` 持久化到 `agent_trace_records`，支持查询：

```txt
GET /api/traces/{traceId}
GET /api/traces?conversationId={conversationId}&limit=50
```

OpenTelemetry trace 用于定位跨服务调用、耗时和错误；`AgentTrace` 用于查看意图路由、工具选择、ReAct、答案生成和反思过程。

## 版本 1.0.0 内容

- 初始化四模块 RAG Agent 架构。
- 提供知识库文档解析、chunk、向量索引、BM25 和 hybrid retrieval 能力。
- 提供查询改写、意图识别和多路由分析服务。
- 提供 Agent 编排服务，支持 RAG 检索、web search 工具和答案引用。
- 提供 React + Vite 前端交互界面。
- 提供示例知识文档、RAG 知识材料、架构文档和本地开发命令。
