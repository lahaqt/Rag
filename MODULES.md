# 模块地图

本仓库是一个多语言工作区：根 `pom.xml` 统一管理三个 Java 服务的版本与 Reactor 构建；前端和评测服务保留各自生态的构建工具，不被强行塞进 Maven。

## 生产运行模块

| 模块 | 入口与端口 | 上游 | 下游 | 稳定边界 |
| --- | --- | --- | --- | --- |
| `frontend` | Vite `5173` | 浏览器 | Agent、知识库 HTTP API | 仅处理交互和展示 |
| `agent-service` | `/api/chat`，`28083` | 前端/A2A | 查询分析、知识库、LLM、MCP、Web Search | 编排、策略、记忆、Trace |
| `query-analysis-service` | `/api/chat/analyze`，`28082` | Agent | LLM（可选） | 分析和建议，不执行能力 |
| `knowledge-service` | `/api/knowledge-bases`、`/api/vector/search`，`28081` | 前端、Agent | PostgreSQL、Redis、对象存储、Embedding | 文档生命周期与检索 |

## 支持模块与资料

| 目录 | 用途 | 是否进入线上请求链路 |
| --- | --- | --- |
| `evaluation-service/` | Python 评测 CLI/API；以 `/api/chat` 为黑盒被测入口 | 否 |
| `docs/contracts/` | `agent-service` 到两个下游服务的版本化 OpenAPI 契约 | 否 |
| `observability/` | OTel Collector 本地配置 | 仅部署支撑 |
| `knowledge-base/` | 本地知识材料/演示语料 | 否，按需导入 |
| `reports/`、`documents/` | 项目报告与材料 | 否 |

## 构建策略

```text
root pom.xml
  ├─ knowledge-service
  ├─ query-analysis-service
  └─ agent-service

frontend/package.json
evaluation-service/pyproject.toml
```

- 在仓库根执行 `mvn verify`，可一次编译并测试所有 Java 服务。
- 在任意 Java 模块执行 `mvn spring-boot:run`，可独立启动该服务。
- 前端使用 `npm run lint && npm run build && npm run test`。
- 评测模块使用 `pytest` 或 `rag-eval`；它不应成为生产服务的运行时依赖。

## 依赖方向

```text
frontend -> agent-service -> query-analysis-service
                       -> knowledge-service
evaluation-service -> agent-service
```

禁止反向依赖：`knowledge-service` 不依赖 Agent 或查询分析服务；查询分析服务不依赖知识库、MCP 或 Web Search；前端不直连数据库和对象存储。若需修改请求/响应字段，先更新 `docs/contracts/` 中的 OpenAPI 文件，再更新提供方、消费方和契约测试。
