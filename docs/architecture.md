# 架构决策与演进边界

## 为什么保留三个 Java 服务

服务划分按变化率和资源模型，而不是按页面或技术名划分：查询分析需要独立迭代提示词/分类策略；Agent 编排需要控制工具、记忆和模型调用；知识库服务需要处理文件、索引与存储。三者独立部署时可以分别扩容，也能避免知识库实现反向渗入 Agent。

因此，本次整理没有把它们合并成“大而全的 RAG 服务”。根 `pom.xml` 只统一构建、Java/Spring Boot 版本和 BOM，不形成运行时耦合。

## 当前需要遵守的规则

1. HTTP 是服务边界；同步契约在 `docs/contracts/`，不建立跨服务业务 DTO 共享包。
2. `agent-service` 是唯一的工具执行和最终回答编排层。
3. `knowledge-service` 是文档事实、索引和检索的唯一所有者。
4. `query-analysis-service` 只分析和建议，必须有可预测的规则回退。
5. `evaluation-service` 保持黑盒；评测逻辑不能进入在线请求热路径。

## 已识别的后续演进点

- `frontend/src/App.tsx` 与 `App.css` 仍偏大。后续 UI 变更应优先按页面领域（chat、knowledge、mcp、trace）拆出组件、类型和 API client，并为抽出的纯函数补充单测；不要在一次后端结构调整中同时重写 UI。
- 三个 Java 服务都有少量相似的 Trace 透传实现。由于服务需可从子目录独立启动，当前保留实现副本；若将来引入共享基础库，应同时提供 Spring Boot starter 或 Maven wrapper/Reactor 开发命令，不能以牺牲独立启动为代价。
- 本地语料、报告、运行日志和构建目录均不是生产模块。它们应保持在顶层支持目录，且不应被服务代码作为隐式运行时依赖。
- 当前 Spring AI Alibaba A2A Nacos starter 在上下文测试中仍会输出一次 Nacos 注册告警，即使配置声明关闭 registry/discovery；测试不因此失败。部署前应以实际使用的 starter 版本确认关闭属性，或将 Nacos registry starter 置于显式启用的部署 profile，避免纯 JSON-RPC 部署产生无效注册尝试。
