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
- `agent-service/`：RAG Agent 编排服务，负责 `/api/chat` 和 `/api/chat/stream`，串联查询分析、工具调用、检索、答案生成和引用返回。
- `query-analysis-service/`：查询分析与改写服务，负责意图分类、路由建议、query rewrite 和多查询生成。
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

## 版本 1.0.0 内容

- 初始化四模块 RAG Agent 架构。
- 提供知识库文档解析、chunk、向量索引、BM25 和 hybrid retrieval 能力。
- 提供查询改写、意图识别和多路由分析服务。
- 提供 Agent 编排服务，支持 RAG 检索、web search 工具和答案引用。
- 提供 React + Vite 前端交互界面。
- 提供示例知识文档、RAG 知识材料、架构文档和本地开发命令。
