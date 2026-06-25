# RAG 项目模块说明

## 生产形态总览

当前项目采用四模块生产架构：

```txt
frontend
  -> agent-service
      -> query-rewrite-service
      -> web_search / tools
      -> storage-layer
      -> LLM Provider
```

核心边界：

- `frontend/` 负责用户交互、知识库入口、聊天消息和引用展示。
- `agent-service/` 负责问答编排，是完整 RAG Agent 的入口。
- `query-rewrite-service/` 负责 query 分析、意图识别、路由建议和 query rewrite。
- `storage-layer/` 负责知识库、文档解析、chunk、索引、BM25/hybrid retrieval 和存储 API。

`storage-layer` 不直接生成最终回答，`query-rewrite-service` 不直接检索知识库，前端不直接访问数据库、pgvector、Redis 或 RustFS。

## agent-service

### Agent orchestration upgrade

`agent-service` now uses an explicit agent pipeline:

```txt
ChatOrchestrator
  -> query analysis fallback
  -> PlanExecuteAgent
      -> AgentPlan
      -> ReActLoop
          -> ToolRegistry
              -> rag_retrieval
              -> web_search
      -> AnswerGenerator
      -> ReflectionCritic
  -> ChatResponse.agentTrace
```

`ToolRegistry` is the single entry point for tool selection. RAG retrieval and web search are both `AgentTool` implementations, so future tools can be added without expanding `ChatOrchestrator`. `AgentTraceStep` records each plan, route, tool observation, answer generation, and reflection step in the chat response.

### 模块定位

`agent-service/` 是生产问答编排层，负责把用户问题串成完整 RAG 链路：

```txt
用户问题
  -> query-rewrite-service 分析意图和改写 query
  -> ToolRouter 判断是否需要外部工具
  -> web_search 等工具处理实时/外部信息问题
  -> storage-layer 检索相关 chunks
  -> 组装 prompt
  -> 调用 LLM
  -> 返回答案、引用、检索调试信息
```

### 服务端口

```txt
Agent API: http://127.0.0.1:28083
```

### 主要接口

```txt
POST /api/chat
POST /api/chat/stream
```

### 运行命令

```bash
cd agent-service
mvn spring-boot:run
```

### 测试

```bash
cd agent-service
mvn test
```

### 关键配置

配置文件：

```txt
agent-service/src/main/resources/application.yml
```

默认下游：

```txt
query-rewrite-service: http://127.0.0.1:28082
storage-layer:          http://127.0.0.1:28081
```

默认工具：

```txt
web_search provider: bing
web_search base-url: https://cn.bing.com
web_search max-results: 5
```

`web_search` 用于处理天气、新闻、最新价格、今天/当前/实时信息等不适合知识库检索的问题。当前默认使用 Bing HTML 搜索作为免 Key 开发兜底；后续生产环境建议替换为 Bing Search API、Tavily、Brave 或企业内部搜索服务。

LLM API Key 默认读取：

```txt
ARK_API_KEY
```

未配置 LLM Key 时，`agent-service` 会返回基于检索片段的降级摘要，便于本地联调。

## query-rewrite-service

### 模块定位

`query-rewrite-service/` 负责前端用户 query 输入后的分析与改写，不承担知识库、文档存储、向量检索或最终答案生成职责。

模块职责：

- 接收 `/api/chat/query-rewrite` 和 `/api/chat/analyze` 请求。
- 结合会话 history 做意图识别、路由建议和 query rewrite。
- 对知识检索路径输出 `rewrittenQuery` 和 `retrievalQueries`。
- 对闲聊、工具调用、澄清路径跳过 query rewrite。
- 读取独立的 `rag.llm` 配置，支持 Ark OpenAI/Anthropic 兼容接口。

### 服务端口

```txt
Query Rewrite API: http://127.0.0.1:28082
```

### 主要接口

```txt
POST /api/chat/query-rewrite
POST /api/chat/analyze
```

### 运行命令

```bash
cd query-rewrite-service
mvn spring-boot:run
```

### 测试

```bash
cd query-rewrite-service
mvn test
```

## storage-layer

### 模块定位

`storage-layer/` 是 RAG 知识库的存储与检索底座，负责知识库元数据、原始文档、文档解析、文本分块、异步索引、向量入库、BM25/hybrid 检索和相关 API。

本模块负责：

- 知识库管理。
- 文档上传、解析、删除、重解析、重建索引。
- Apache Tika 文本提取。
- 文本清洗和 chunk。
- PostgreSQL 元数据存储。
- RustFS 原始文件对象存储。
- Redis Stream 文档处理事件。
- pgvector 向量索引。
- `/api/vector/search` 检索接口。

本模块不负责：

- 会话管理。
- Prompt 编排。
- 大模型最终回答生成。
- 前端页面展示逻辑。

### 服务端口

```txt
Storage API:            http://127.0.0.1:28081
PostgreSQL + pgvector:  localhost:25432
Redis Stream:           localhost:26380
RustFS S3 API:          http://localhost:29100
RustFS Console:         http://localhost:29101
```

### 核心 API

```txt
GET    /api/knowledge-bases
POST   /api/knowledge-bases
GET    /api/knowledge-bases/{id}/documents
POST   /api/knowledge-bases/{id}/documents
GET    /api/knowledge-bases/{id}/documents/{documentId}
GET    /api/knowledge-bases/{id}/documents/{documentId}/chunks
POST   /api/knowledge-bases/{id}/documents/{documentId}/reparse
POST   /api/knowledge-bases/{id}/documents/{documentId}/reindex
DELETE /api/knowledge-bases/{id}/documents/{documentId}
POST   /api/vector/search
GET    /api/vector/status
```

### 运行命令

```bash
cd storage-layer
docker compose up -d
mvn spring-boot:run
```

### 测试

```bash
cd storage-layer
mvn test
```

## frontend

### 模块定位

`frontend/` 是 RAG Agent 知识问答系统的用户交互层，负责聊天界面、知识库入口、资料上传入口、引用展示和检索参数面板。

### 服务端口

```txt
Vite Dev Server: http://127.0.0.1:5173
```

### 代理规则

```txt
/api/chat/* -> http://127.0.0.1:28083
/api/*      -> http://127.0.0.1:28081
```

因此聊天请求会先进入 `agent-service`，知识库管理和上传请求继续进入 `storage-layer`。

### 运行命令

```bash
cd frontend
npm install
npm run dev
```

### 构建与检查

```bash
cd frontend
npm run lint
npm run build
```

## 推荐本地启动顺序

```bash
cd storage-layer
docker compose up -d
mvn spring-boot:run
```

```bash
cd query-rewrite-service
mvn spring-boot:run
```

```bash
cd agent-service
mvn spring-boot:run
```

```bash
cd frontend
npm run dev
```

访问：

```txt
http://127.0.0.1:5173
```
