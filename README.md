# RAG Agent Workspace

版本：`1.2.0-dev`

这是一个本地可运行的 RAG Agent 多模块工作区，覆盖知识库文档管理、查询分析与改写、Agent 编排、会话记忆、MCP 工具管理、可观测性和前端交互。整体链路从前端问题输入开始，经由 Agent 服务完成意图分析、工具路由、知识库检索、Prompt 组装和答案生成。

## 当前开发现状

当前主线已经从“知识库检索 Demo”演进为四服务 RAG Agent 工作区：

- 普通问答入口是 `agent-service` 的 `/api/chat` 和 `/api/chat/stream`，内部串联 `query-analysis-service` 与 `knowledge-service`。
- `query-analysis-service` 已承担第一层意图树分析、路由建议、执行模式判断、query rewrite 和多检索查询生成。
- `agent-service` 已落地 Plan-Execute、ReAct 工具循环、ReflectionCritic、`agentTrace`、SSE 流式输出、会话历史、会话记忆、MCP 工具接入、反馈记录 API，以及默认由 Spring AI Alibaba `StateGraph` 驱动的 multi-agent 探索入口。
- `knowledge-service` 已落地 PostgreSQL 元数据、RustFS 原文对象存储、Redis Stream 文档任务、Apache Tika 解析、recursive chunk、pgvector、BM25 和 hybrid retrieval。
- `frontend` 已提供聊天、流式回答、会话列表、知识库管理、文档上传、检索状态、MCP 管理和 slash command 入口。

`/api/chat/multi-agent` 与 `/api/chat/multi-agent/stream` 是 Spring AI Alibaba multi-agent 探索入口；生产问答主链路仍以 `/api/chat` 为准。

## 模块结构

```txt
frontend
  -> agent-service
      -> query-analysis-service
      -> knowledge-service
      -> web_search / tools
      -> LLM Provider
```

- `frontend/`：React 19 + TypeScript + Vite 前端，提供聊天、流式回答、会话列表、知识库管理、文档上传、MCP 管理、引用展示和检索参数控制。
- `agent-service/`：RAG Agent 编排服务，负责 `/api/chat`、`/api/chat/stream`、`/api/conversations`、`/api/mcp/servers`、`/api/traces` 和 `/api/feedback`，串联意图树消费、执行模式选择、工具调用、检索、答案生成和引用返回。
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
npm run test
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

## Agent 服务接口

```txt
POST /api/chat
POST /api/chat/stream
POST /api/chat/multi-agent
POST /api/chat/multi-agent/stream
GET  /api/chat/multi-agent/agents
```

A2A 兼容 facade 接口保留用于外部协议接入和任务查询，不参与 multi-agent 编排实现：

```txt
GET  /api/chat/multi-agent/tasks/{taskId}
POST /api/chat/multi-agent/tasks/{taskId}/cancel
POST /api/chat/multi-agent/a2a
GET  /.well-known/agent.json
```

会话、MCP、Trace 与反馈接口：

```txt
GET    /api/conversations
POST   /api/conversations
GET    /api/conversations/{id}
GET    /api/conversations/{id}/messages
PATCH  /api/conversations/{id}
DELETE /api/conversations/{id}

GET    /api/mcp/servers
POST   /api/mcp/servers
PUT    /api/mcp/servers/{id}
DELETE /api/mcp/servers/{id}
POST   /api/mcp/servers/{id}/refresh
POST   /api/mcp/servers/{id}/tools/{toolName}/call

GET    /api/traces/{traceId}
GET    /api/traces?conversationId={conversationId}&limit=50
POST   /api/feedback
GET    /api/feedback?conversationId={conversationId}&limit=50
```

SSE 流式接口事件包括 `metadata`、`trace_delta`、`answer_delta`、`answer_reset`、`citations`、`done` 和 `error`。

`/api/chat/multi-agent` 是独立探索链路，不影响普通 `/api/chat`。multi-agent 编排完全由 Spring AI Alibaba `StateGraph` 接管；每次请求会进入 `prepare_request -> plan_agents -> run_specialist_agents -> finish_task` 图节点，可按 `requiredCapabilities` 和 supervisor 决策顺序执行 Knowledge/WebSearch/MCP/FollowUp specialist。项目不再保留自建 A2A runtime 或 `rag.multi-agent.runtime` 回退开关。

## 会话记忆与历史

`agent-service` 同时维护两类会话能力：

- 会话历史：通过 PostgreSQL 表保存 conversation 与 message，供前端会话列表、搜索、置顶、归档和消息恢复使用。
- 会话记忆：通过 `rag.memory.*` 配置控制短期上下文、滚动摘要、结构化状态、长期语义记忆和用户画像事实。

默认 `rag.memory.provider` 为 `in-memory`，可切换为 `redis` 或 `postgres` 管理会话消息、摘要和状态；`rag.memory.summary-mode` 默认为 `window`，也可以设置为 `llm`。长期语义记忆和用户画像已通过 `PostgresSemanticMemoryStore`、`PostgresUserProfileStore` 持久化，语义召回由 `rag.memory.semantic-embedding.*` 控制。

## MCP 工具管理

MCP 管理由 `agent-service` 承担，前端只是配置和测试调用界面。当前支持：

- `streamable_http` 服务：endpoint、bearer token、启用状态、刷新工具列表。
- `stdio` 服务：command、args、environment、working directory、启用状态。
- 工具发现、健康状态刷新和单工具 JSON 参数测试调用。

启用的 MCP 工具会作为 `mcp_tool` 能力进入 `ToolRegistry`，由 Agent 在工具型请求中选择。

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

## 版本 1.2.0-dev 内容

- 四模块职责已稳定为 `frontend`、`agent-service`、`query-analysis-service`、`knowledge-service`。
- 普通 `/api/chat` 链路已支持意图树、Plan-Execute、ReAct、反思重试、工具路由、引用返回和 AgentTrace。
- 新增 SSE 流式聊天、多 Agent 流式入口、Spring AI Alibaba multi-agent 图运行时、会话历史、持久化语义记忆、MCP 管理、Trace 查询和反馈记录。
- 知识库侧保持 pgvector + BM25 + hybrid retrieval，文档处理链路为 RustFS 原文、Redis Stream 任务、PostgreSQL 元数据和向量索引。
- 前端新增会话管理、MCP 管理、流式回答和 slash command 交互；构建校验包含 `npm run lint`、`npm run build` 和 `npm run test`。
