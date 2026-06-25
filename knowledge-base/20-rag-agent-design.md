---
title: "RAG Agent 设计：编排层、工具路由、多步检索与自我反思"
description: "系统阐述 RAG Agent 编排层职责、ToolRouter 与 web_search 接入、多步检索与自我反思机制，并对照本项目 agent-service 生产链路落地。"
category: "Agent 与工具"
subcategory: "RAG Agent"
tags: [RAG Agent, 编排, 工具路由, 多步检索, 自我反思]
keywords: [RAG Agent, 编排层, ToolRouter, web_search, 多步检索, 自我反思]
difficulty: "专家"
audience: "架构师、Agent 工程师"
related_topics: ["Agent 架构与 ReAct", "意图识别与多路由调度", "查询重写与语义增强", "RAG 系统整体架构"]
author: "RAG Knowledge Base"
source: "internal-knowledge-base"
language: "zh-CN"
created: "2026-06-24"
updated: "2026-06-24"
version: "1.0"
---

# RAG Agent 设计：编排层、工具路由、多步检索与自我反思

## 一、从单次 RAG 到 RAG Agent 的演进

经典 RAG 是一条**单向单步**的流水线：`query → embed → 检索 topK → 拼接 prompt → LLM 生成`。它默认三个前提同时成立——用户问题已经足够清晰、知识库一定能覆盖答案、一次检索就足够充分。但这三个前提在真实企业场景里几乎从不成立：用户会问"这个和我们上周讨论的那个一样吗"，知识库里没有"今天的天气"，而一个需要对比三份合同条款的问题，一次检索根本召不全证据。

RAG Agent 的本质，是把这条单向流水线升级为**带感知、带决策、带回路**的智能体循环。它不再是"检索完就生成"，而是先理解问题要不要改写、该走知识库还是该联网、检索回来的证据够不够、生成的答案能不能自圆其说，并在任何一环判定不达标时主动补检或重答。换言之，Agent 把 RAG 从"一次函数调用"变成了"一个有目标、有状态、会自我纠错的执行过程"。

演进的关键差异可归纳为四点：

- **编排层显式化**：单次 RAG 把流程埋在一段代码里，Agent 把流程拆成可观测、可替换、可编排的显式阶段（分析 / 路由 / 检索 / 生成 / 反思）。
- **工具能力接入**：检索不再是唯一信息来源，`web_search`、企业内部 API、数据库查询等工具按需被路由调用。
- **多步检索**：允许把问题分解为子问题分别检索，或在证据不足时补检，而非"一锤子买卖"。
- **自我反思**：在生成前后增加判别环节，评估检索充分性与答案可信度，决定是否需要再次检索或改写。

## 二、编排层职责：串联 query 分析 / 路由 / 检索 / 生成

编排层（Orchestrator）是 RAG Agent 的"中枢神经"，它本身不产生知识，而是**协调多个子系统的执行顺序、传递上下文、处理失败与降级**。一个合格的编排层至少要承担以下职责：

```
               ┌──────────────── RAG Agent 编排层 ─────────────────┐
               │                                                  │
  用户问题 ───▶│  1) Query 分析     ── 意图识别 / query 改写 / 子问题分解
               │        │                                         │
               │        ▼                                         │
               │  2) 工具路由(ToolRouter) ── 知识库? web_search? 工具?
               │        │                                         │
               │   ┌────┴────┐                                    │
               │   ▼         ▼                                    │
               │ web_search  知识库检索(可多步 / 子问题并行)        │
               │   │         │  ▲                                │
               │   │         │  │ 3) 自我反思: 证据是否充分?        │
               │   │         │  │   不足 → 补检 / 重写后重检       │
               │   │         │  │   充分 → 进入生成                │
               │   └────┬────┘                                    │
               │        ▼                                         │
               │  4) Prompt 组装 + LLM 生成                       │
               │        │                                         │
               │        ▼                                         │
               │  5) 答案自评 + 引用标注 + 检索调试回传            │
               │        │                                         │
  答案/引用/调试 ──┘                                            │
               └──────────────────────────────────────────────────┘
```

职责要点如下：

- **Query 分析前置**：在检索之前先判明意图（`knowledge` / `tool` / `chitchat` / `clarification`）、是否需要改写、是否需要分解子问题。这一步的输出（改写后的 query、子检索查询列表、路由建议）直接决定后续所有环节的走向。
- **路由决策集中化**：所有"要不要联网、要不要检索、要不要走工具"的判断收敛到 `ToolRouter`，避免散落在各处导致行为不可预测。
- **检索与生成解耦**：检索返回的是结构化 `RetrievalHit`（含 chunk 内容、分数、文档名、chunkId），生成阶段只依赖这些证据，不直接耦合底层存储协议，便于替换向量库。
- **失败与降级显式化**：每一步都要回答"失败了怎么办"——分析失败用兜底分析、检索失败用空命中、LLM 失败用检索摘要降级，而不是让整条链路抛 500。
- **可观测性内建**：编排层必须把每一步的关键决策（用了哪个工具、命中几条、是否调用了 LLM、finishReason）记录并回传，这是后续调试与优化的基础。

## 三、工具路由（ToolRouter）与 web_search 接入

`ToolRouter` 的职责是回答一个问题：**这个 query 应该由谁来回答？** 它是 Agent 区别于单次 RAG 的第一道分水岭。一个健壮的 ToolRouter 通常结合两类信号做决策：

- **关键词启发式**：包含"今天/实时/新闻/天气/股价/汇率"等词，强烈暗示需要外部实时信息，命中即走 `web_search`。启发式的好处是零延迟、可解释、不依赖 LLM；缺点是覆盖不全。
- **意图分析信号**：当 query-rewrite-service 给出 `intent=tool` 或 `route=tool_invocation` 时，认定为工具路径。这覆盖了关键词没命中的语义级实时需求。

两者是"或"关系而非"且"——任一命中即路由到 `web_search`，都不命中则走知识库检索。`ToolDecision` 作为路由结果的标准载体，需携带四个字段：`useTool`（是否用工具）、`toolName`（如 `web_search`）、`query`（实际下发给工具的查询串）、`reason`（决策依据，用于可观测性）。`reason` 字段看似可有可无，实则是生产环境排查"为什么走了联网"的唯一线索，绝不可省。

`web_search` 接入的设计要点：

- **查询增强（query enrichment）**：天气类问题剥离口语词并补齐"天气""预报"关键词，日期类问题追加当天日期，使搜索引擎更容易返回结构化结果。这一步在 ToolRouter 内完成，而非交给下游工具。
- **接口抽象**：`WebSearchTool` 定义为单一方法接口 `search(query) → List<WebSearchResult>`，具体实现可从免 Key 的 HTML 抓取切换到 Bing Search API、Tavily、Brave 或企业内搜，编排层无感知。
- **结果归一化**：统一为 `index / title / url / snippet` 结构，与 `RetrievalHit` 平行，让 PromptBuilder 用同一套引用编号逻辑渲染。
- **失败兜底**：web_search 调用失败时，返回明确的"联网搜索失败"文案与 `web_search_failed` 标记，绝不静默吞异常导致空答案。

## 四、多步检索：检索不够再补检 / 子问题分解检索

单次检索的根因缺陷在于"一次 query + 一次 topK"无法覆盖需要跨文档、跨概念聚合的复杂问题。多步检索从两个方向缓解这一问题：

**子问题分解检索**。当 query-rewrite-service 输出多个 `retrievalQueries`（子问题或同义改写）时，编排层对每个子查询各发一次向量检索，再把结果按 chunk 去重、按分数全局排序、截断到 topK。这样一份证据集来自多视角查询，召回率显著高于单 query。去重键通常取 `knowledgeBaseId:documentId:chunkId`，保证同一 chunk 不会被重复计入上下文。

**证据不足补检（iterative retrieval）**。这是 Agent 的核心回路：第一轮检索后，若命中数低于阈值、或分数普遍偏低、或最高分低于相似度门槛，则判定"检索不充分"，触发改写 query 或扩大 topK 后二次检索。更高级的形态会让 LLM 先生成一个"要回答此问题还需要知道什么"的追问，再据此补检——即 ReAct 的 Thought→Action→Observation 循环在检索域的特化。

多步检索的设计权衡：

- **延迟与质量的取舍**：每多一轮检索，端到端延迟翻倍。生产中通常把补检轮次硬上限设为 1~2 轮，避免无界循环。
- **去重与排序的全局性**：多 query 结果必须跨 query 全局排序，否则会出现"第二个子 query 的低分 chunk 挤掉第一个的高分 chunk"。
- **可观测性**：每轮检索的 query、命中数、是否触发补检都应记录，否则多步检索会变成无法调优的黑盒。
- **何时终止**：终止条件不能只看"命中数 > 0"，而应综合"最高分是否过门槛""是否覆盖了问题关键实体"。纯数量门槛会让一条低相关命中误判为"已充分"。

## 五、自我反思与答案自评

自我反思（self-reflection）是 RAG Agent 区别于"多步检索 + 生成"的进阶能力，它回答三个递进的问题：

1. **检索是否充分？** 评估证据数量、相关性分数、是否覆盖问题所需的关键实体。不充分则触发补检或改写重检。
2. **答案是否可信？** 评估生成内容是否每一条都由证据支撑、是否出现幻觉、是否引用了不存在的文档名或条款。不可信则要求 LLM 重答或降级为纯证据摘要。
3. **是否需要再检索？** 当答案中出现"知识库未提及"的断言，但问题本身属于知识库域，则说明检索召回不全，应回到第四步补检。

一个工程上可落地的自评机制，通常是在生成 prompt 中内嵌约束（"不能从片段推出的内容，要明确说明没有依据""不要编造文档名、政策条款或系统状态"），并配合 `finishReason` 字段做结构化标记。本项目通过 `AnswerDraft.finishReason` 把每次生成的"出生原因"显式编码——如 `llm_generated` / `no_retrieval_hits` / `llm_failed_retrieval_fallback` / `clarification_required` / `tool_not_connected`——这本身就是一种轻量自评：它不依赖额外的判别模型，却让下游（前端、监控、人工审核）能立刻知道这个答案处于什么置信状态。

更完整的反思回路需要引入"判别器"角色：用一次独立的 LLM 调用评估 `(问题, 证据, 答案)` 三元组，输出 `sufficient / insufficient / hallucinated`，并在 `insufficient` 时回传"还缺什么证据"以驱动补检。该机制成本较高，生产中通常只对高价值查询或低置信答案触发，而非全量启用。

## 六、流式输出

长答案若一次性返回，用户感知延迟可达数秒，体验极差。流式输出把"等待答案"变成"看着答案逐字出现"，是生产 RAG Agent 的标配。

流式实现的关键设计：

- **事件分型**：SSE 流不能只发文本 delta，而应分阶段下发 `metadata`（意图、路由、改写后 query、工具名、finishReason，让前端先渲染"正在做什么"）、`delta`（答案文本切片）、`citations`（引用列表）、`done`（完整响应）、`error`（异常）。前端据此能展示"已识别为知识检索问题 → 正在检索 → 正在生成"的过程态。
- **元数据先行**：在第一个 delta 之前就把路由与意图元数据推下去，使前端可以在等待生成时同步展示检索过程，掩盖检索延迟。
- **切片粒度**：delta 按固定字符数切片（如 64 字），过小导致 SSE 帧开销过大，过粗失去流式观感。
- **异常隔离**：流式场景下异常不能抛 500（连接已建立），必须通过 `error` 事件优雅下发并 `completeWithError`，同时容忍客户端已断开的 IOException。
- **生成侧真流式**：当前实现是"先生成完整答案再切片下发"，适合初版；进阶应接入 LLM 的 token 级流式接口，做到首 token 即下发。

## 七、错误兜底

Agent 链路任一环失败都不应导致整条链路崩溃，兜底设计要"分层、有标记、可降级"：

- **Query 分析失败**：用兜底分析（`intent=knowledge`、`route=knowledge_retrieval`、`confidence=0.50`、标记 `query_analysis_fallback`）继续，保证链路不中断。
- **检索为空**：若 `shouldRetrieve` 判定需检索但命中为空，返回 `no_retrieval_hits` 文案引导用户换问法或上传文档，而非编造答案。
- **LLM 未配置 / 失败**：返回基于检索片段的降级摘要（`llm_not_configured_retrieval_fallback` / `llm_failed_retrieval_fallback`），保证本地无 Key 也能联调。
- **web_search 失败**：返回明确失败文案与 `web_search_failed` 标记，提示检查网络或切换生产搜索 API。
- **意图为 clarification / tool / chitchat**：分别走"请补充信息""工具未接入""直接回复"分支，不进入知识库检索与 LLM 生成，省成本且更准确。

每条兜底路径都必须带一个语义化的 `finishReason`，这是事后判断"这个答案该不该信"的唯一线索。

## 八、可观测性与调试信息（检索调试回传）

RAG Agent 最大的运维痛点是"答案错了，但不知道哪一步错了"。可观测性的核心是把**决策链路全量回传**，而非只回传最终答案。

具体落地：

- **检索调试开关**：通过 `ChatOptions.includeRetrievalDebug` 控制，开启时在响应中回传完整的 `retrievalHits`（含 chunkId、分数、文档名、内容），关闭时只回传 `citations`（精简引用）。这样生产默认关闭以省带宽，排障时按需开启。
- **回传字段全集**：`ChatResponse` 同时携带 `intent`、`route`、`originalQuery`、`rewrittenQuery`、`retrievalQueries`、`citations`、`retrievalHits`、`llmUsed`、`finishReason`、`toolName`、`webSearchResults`——覆盖从分析到生成的全链路状态。
- **结构化日志**：每次回答记录 `conversationId / knowledgeBase / intent / route / hits / tool / resultCount / llmUsed / finishReason`，便于聚合分析"哪类问题命中率低""哪个工具失败率高"。
- **finishReason 体系**：把所有非正常路径编码为枚举式字符串，是可观测性的最小可用单元，比自由文本日志更利于聚合。

## 九、本项目对照：agent-service 如何落地 RAG Agent 编排

本项目的 `agent-service`（`C:\Users\ASUS\Desktop\Codex\Rag\agent-service`）是生产问答编排层，对外暴露 `POST /api/chat` 与 `POST /api/chat/stream`（端口 28083），完整落地了上述 RAG Agent 设计。其链路为：

```
用户问题
  → query-rewrite-service 分析意图和改写 query   (HttpQueryAnalysisClient, 端口 28082)
  → ToolRouter 判断是否需要外部工具              (ToolRouter.decide)
  → web_search 等工具处理实时/外部信息问题       (WebSearchTool / DuckDuckGoWebSearchTool)
  → storage-layer 检索相关 chunks               (HttpStorageRetrievalClient, 端口 28081)
  → 组装 prompt                                 (PromptBuilder)
  → 调用 LLM                                    (LlmGateway / LlmChatClient, ARK_API_KEY)
  → 返回答案、引用、检索调试信息                 (ChatResponse)
```

逐项对照说明：

- **编排核心 `ChatOrchestrator`**：`answer()` 方法是整个 Agent 的入口，依次执行 `analyze → toolRouter.decide → (webSearch 分支 | retrieve 分支) → generate`，并内建多层 try-catch 兜底。它通过构造器注入 `QueryAnalysisClient / StorageRetrievalClient / AnswerGenerator / ToolRouter / WebSearchTool / RagProperties`，职责边界清晰。
- **工具路由 `ToolRouter`**：`decide()` 先用关键词启发式（"今天/实时/天气/股价/最新..."等中英文词）命中即返回 `ToolDecision.webSearch`，再判断 `analysis.intent()=="tool"` 或 `route=="tool_invocation"`，并通过 `enrichQuery()` 对天气类问题做关键词补齐、对日期类问题追加当天日期。`reason` 字段记录 `contains_realtime_keyword:xxx` 或 `query_analysis_routed_to_tool`，完全符合"决策可解释"要求。
- **多步检索**：`retrieve()` 对 `retrievalQueries`（来自 query-rewrite-service 的子问题列表）合并 `rewrittenQuery` 与原始 query 逐个发检索，结果按 `knowledgeBaseId:documentId:chunkId` 去重、按分数全局降序、截断到 topK。这正是"子问题分解检索 + 全局去重排序"的落地。注意当前为单轮检索，未实现证据不足自动补检的回路——这是后续可演进点。
- **自我反思（轻量形态）**：通过 `AnswerDraft.finishReason` 把每次生成的状态显式编码（`llm_generated` / `no_retrieval_hits` / `llm_failed_retrieval_fallback` / `clarification_required` / `tool_not_connected` / `web_search_llm_generated` 等），并在 `PromptBuilder` 的 system prompt 内嵌"不能从片段推出的内容要说明没有依据""不要编造文档名、政策条款"等约束，构成"约束式自评 + 结构化标记"的轻量反思。完整判别器回路尚未接入。
- **流式输出**：`/api/chat/stream` 用 `SseEmitter` 分阶段下发 `metadata`（intent/route/rewrittenQuery/toolName/finishReason）、`delta`（64 字切片）、`citations`、`done`、`error`，元数据先于文本，异常通过 `error` 事件优雅处理，完全符合生产流式范式。
- **错误兜底**：`analyze()` 失败回退兜底分析；`generate()` 对 clarification/tool/chitchat/空知识库/空命中/LLM 未配置/LLM 失败各有独立分支与 `finishReason`；web_search 失败返回 `web_search_failed` 文案。
- **可观测性**：`ChatResponse` 携带全链路字段，`includeRetrievalDebug` 开关控制是否回传完整 `retrievalHits`；每次回答打印结构化日志（conversation/tool/hits/llmUsed/finishReason）。

生产化考量与演进建议：当前 `web_search` 默认用免 Key 的 HTML 抓取作为开发兜底，生产应替换为 Bing Search API / Tavily / Brave 或企业内搜；多步检索可加入"命中数或最高分不足触发一轮改写补检"的回路；自我反思可引入独立判别 LLM 对高价值查询做答案可信度评估；流式可从"生成后切片"升级为 LLM token 级真流式以进一步降低首 token 延迟。

## 小结

RAG Agent 的设计内核是把"检索 + 生成"这条单向流水线，重构为一个由**编排层**驱动、带**工具路由**、支持**多步检索**、具备**自我反思**的智能体循环。编排层是中枢，负责显式串联 query 分析、路由、检索、生成与反思；ToolRouter 决定 query 该走知识库还是 web_search，是 Agent 区别于单次 RAG 的第一道分水岭；多步检索通过子问题分解与证据不足补检，突破单次检索的召回上限；自我反思在生成前后评估检索充分性与答案可信度，决定是否回环。配合流式输出、分层错误兜底与全链路可观测性，才构成一个能在生产稳定运行、可调试、可演进的 RAG Agent。本项目的 `agent-service` 以 `ChatOrchestrator` 为核心，完整落地了这一编排范式，并在 ToolRouter、多查询去重检索、finishReason 体系、SSE 分阶段流式与检索调试回传等关键点上给出了可参照的生产实现。
