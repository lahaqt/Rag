# Agent Service

`agent-service` 是生产问答的唯一编排入口，默认监听 `http://127.0.0.1:28083`。它不实现文档解析或检索存储，而是消费查询分析结果、按策略调用能力，并组装最终回答。

## 职责

- 暴露 `/api/chat`、`/api/chat/stream`、会话、MCP、Trace 与反馈 API。
- 以 `SpringAiAlibabaAgentRuntime` 统一运行普通与 multi-agent 图。
- 管理会话历史、短期/长期记忆、业务 Trace 与反馈持久化。
- 对 `rag_retrieval`、`web_search` 和 `mcp_tool` 施加路由、并发、重试和失败隔离策略。

## 普通请求路径

```text
ChatController -> ChatOrchestrator -> SpringAiAlibabaAgentRuntime
  -> prepare_context -> query_analysis -> route_capabilities
  -> execute_capability -> generate_answer -> reflect_answer -> finalize_response
```

普通图只选择一个能力分支；`/api/chat/multi-agent` 才会并行调度知识检索、Web Search 与 MCP 分支。`query-analysis-service` 给出的 `requiredCapabilities` 是声明式约束，最终是否调用工具仍由本模块的策略决定。

## 下游契约

- 查询分析：`docs/contracts/query-analysis-v1.openapi.yaml`
- 知识检索：`docs/contracts/knowledge-retrieval-v1.openapi.yaml`

不要把下游 DTO 复制进本模块或把工具执行逻辑放入 Controller。HTTP 客户端位于 `client/`，业务边界在 `service/`，对外协议在 `controller/` 和 `a2a/`。

## 运行与验证

```powershell
cd agent-service
mvn spring-boot:run
mvn test
```

启动前需要可访问的查询分析服务和知识库服务；未配置 LLM Key 时，系统会走可用于本地联调的降级回答路径。核心配置见 `src/main/resources/application.yml`。
