# Query Analysis Service

`query-analysis-service` 是用户输入进入 RAG 链路后的第一层语义网关，默认监听 `http://127.0.0.1:28082`。

## 职责与禁止项

本服务负责：意图识别、路由建议、执行模式判断、查询改写、检索查询生成和澄清问题生成。它可以使用 LLM JSON 分类器，并在模型未配置、失败或输出不合法时回退到规则分类器。

本服务不访问知识库、不调用 MCP/Web Search、不管理会话持久化，也不生成最终回答。`requiredCapabilities` 只是给 `agent-service` 的声明式建议，不是工具执行权限。

## API

```text
POST /api/chat/analyze
POST /api/chat/query-rewrite
```

契约以 [query-analysis-v1.openapi.yaml](../docs/contracts/query-analysis-v1.openapi.yaml) 为准。修改字段时先更新契约，再同步更新调用方 `agent-service` 的 HTTP 客户端及其契约测试。

## 包结构

- `controller/`：HTTP 入口。
- `service/`：分类、改写和历史格式化。
- `dto/`：仅本服务 HTTP DTO。
- `config/`：LLM、CORS 和服务配置。
- `observability/`：Trace 上下文透传。

## 运行与验证

```powershell
cd query-analysis-service
mvn spring-boot:run
mvn test
```

配置位于 `src/main/resources/application.yml`。通过 `ARK_API_KEY` 注入模型密钥；不要把密钥写入 YAML 或提交到仓库。
