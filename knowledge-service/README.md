# RAG 知识库服务 knowledge-service

`knowledge-service` 是知识库系统的服务模块，负责知识库元数据、原始文档、文档解析、文本分块、异步索引任务、向量入库和向量检索。

本模块不负责大模型问答编排，也不直接生成最终回答。问答/Agent 模块应通过知识库检索接口获取相关 chunk，再组织 Prompt 调用大模型。

## 模块边界

本模块负责：

- 知识库管理：创建、查询知识库。
- 文档管理：上传、查询、删除、重新解析文档。
- Ingest：接收前端上传文件并保存原始文件。
- Parse：使用 Apache Tika 提取文本和文档元数据。
- Clean：对 Tika 输出文本做基础清洗。
- Chunk：按配置策略切分可检索文本块。
- Metadata Store：将知识库、文档、chunk 元数据写入 PostgreSQL。
- Object Store：将原始文件写入 RustFS。
- Task Queue：通过 Redis Stream 发布文档处理事件。
- Vector Index：消费文档解析事件，调用 Embedding 模型并写入 pgvector。
- Vector Search：提供向量检索接口。

本模块不负责：

- 会话管理。
- Prompt 编排。
- 大模型回答生成。
- 前端页面展示逻辑。

## 存储组件

```txt
PostgreSQL  -> 存储知识库、文档、chunk 等结构化元数据
pgvector    -> 存储 chunk embedding，并提供 ANN 检索
RustFS      -> 存储用户上传的原始文件
Redis Stream -> 发布和消费文档解析/索引任务
```

当前本地端口：

```txt
Spring Boot API:       http://127.0.0.1:28081
PostgreSQL + pgvector: localhost:25432
Redis Stream:          localhost:26380
RustFS S3 API:         http://localhost:29100
RustFS Console:        http://localhost:29101
```

## Ingest 架构

上传入口：

```txt
POST /api/knowledge-bases/{id}/documents
```

处理流程：

```txt
前端上传文件
  -> Spring Boot 接收 MultipartFile
  -> 生成 documentId
  -> 保存原始文件到 RustFS
  -> 使用 Apache Tika 解析临时文件
  -> 清洗解析文本
  -> 执行 chunk 分块
  -> 文档元数据和 chunk 写入 PostgreSQL
  -> 发布 DOCUMENT_PARSED 到 Redis Stream
  -> Redis 消费者线程异步执行 embedding 和 pgvector 入库
```

采用该流程的原因：

- 原始文件和解析结果分离，便于后续重新解析。
- 文档元数据和 chunk 使用 PostgreSQL 事务化管理。
- embedding 是外部 API 调用，耗时和失败风险较高，放到 Redis Stream 异步消费更适合生产。
- Redis Stream 有消费组、pending、ack 机制，后续可以扩展重试和多消费者。

## Parse 策略

解析工具：

```txt
Apache Tika
```

当前职责：

- 自动识别文档类型。
- 提取正文文本。
- 提取 Tika metadata，例如 `Content-Type`、编码、解析器信息。

当前已验证文本文件可以正常解析。PDF、Word 等格式由 Tika parser 包支持，后续可按实际文件类型继续补充解析质量测试。

解析失败时：

- 文档状态写为 `FAILED`。
- `errorMessage` 保存失败原因。
- 不生成 chunk。
- 不进入向量索引。

## Clean 策略

Tika 输出的文本不会直接进入向量化，会先做基础清洗：

- 统一换行符。
- 将 tab 等空白字符规整为空格。
- 合并连续多余空行。
- 移除独立 URL 行。
- 移除明显页码行。
- 去除首尾空白。

原因：PDF/OCR/网页类文本常混入页眉、页脚、页码、链接等噪声。如果不清洗，会降低 chunk 质量和检索准确率。

## Chunk 架构与策略

当前默认配置：

```yaml
rag:
  chunking:
    strategy: recursive
    chunk-size: 500
    chunk-overlap: 50
    min-chunk-size: 120
```

默认采用 `recursive` 递归分块策略。

递归分块的切分优先级：

```txt
空行
-> 换行
-> 中文句号/感叹号/问号
-> 分号
-> 英文句号
-> 逗号
-> 空格
-> 固定长度兜底
```

采用原因：

- 比固定长度分块更容易保留段落和句子语义。
- 比纯语义分块成本低，不需要额外模型调用。
- 适合知识库、产品文档、政策说明、普通 PDF/Word 文档。
- `chunk-size=500`、`chunk-overlap=50` 是较稳的起始值，重叠比例约 10%。

当前支持的策略：

```txt
fixed      -> 固定大小分块，适合日志或兜底场景
overlap    -> 固定窗口 + 重叠，适合 OCR、格式混乱文本
recursive  -> 默认策略，适合大多数知识库文档
hybrid     -> 混合策略，按文件/内容类型选择分块方式
```

`hybrid` 当前规则：

- `.log` 文件按行聚合。
- FAQ 文本按 `Q:/A:`、`问:/答:` 问答对切分。
- 其他文本回退到 `recursive`。

更详细的分块方案见：

```txt
docs/chunking-strategies.md
```

## 异步索引架构

文档解析和分块完成后，服务会发布事件：

```txt
Redis Stream: document-tasks
eventType: DOCUMENT_PARSED
knowledgeBaseId: ...
documentId: ...
chunkCount: ...
```

消费者配置：

```yaml
rag:
  queue:
    provider: redis
    stream: document-tasks
    consumer-group: storage-indexers
    consumer-name: knowledge-service-1
```

消费者线程启动后会：

```txt
读取 DOCUMENT_PARSED
  -> 根据 knowledgeBaseId/documentId 加载文档和 chunks
  -> 调用 VectorIndexPort
  -> 调用 EmbeddingClient
  -> 批量写入 pgvector
  -> ACK Redis Stream 消息
```

处理成功后可以通过接口查看向量状态：

```txt
GET /api/vector/status
```

当前已验证：

```txt
embeddingProvider: siliconflow
collection: rag_chunks_qwen3_1024
vectorCount: 已随文档自动增加
```

## Embedding 与 pgvector 策略

当前 embedding provider：

```yaml
rag:
  vector:
    embedding:
      provider: siliconflow
      base-url: https://api.siliconflow.cn/v1
      model: Qwen/Qwen3-Embedding-8B
      dimensions: 1024
```

说明：

- `Qwen/Qwen3-Embedding-8B` 支持更高维度输出。
- 当前使用 `1024` 维，是因为 pgvector 的 HNSW 索引不支持超过 2000 维。
- 如果使用 `4096` 维，可以存储向量，但不能创建 HNSW 索引，数据量大时检索性能会受影响。
- 当前 collection 为 `rag_chunks_qwen3_1024`，表结构是 `vector(1024)`。

## RAG 检索策略

当前检索方案采用多路召回：

```txt
多 Query 扩展
  -> 向量检索 Dense Retrieval
  -> BM25 关键词检索 Sparse Retrieval
  -> RRF 融合排序
  -> 返回 Top K chunks
```

默认检索模式：

```txt
retrievalMode: hybrid
queryExpansionEnabled: true
queryExpansionCount: 4
```

### 多 Query 扩展

当前使用轻量规则型 Query 扩展，不额外调用 Chat 模型：

- 原始问题。
- 去除“什么、怎么、如何、多久”等疑问词后的版本。
- 提取英文、数字、型号等精确术语后的版本。
- 去空格版本，提升中文连续文本和型号匹配概率。

后续如果需要更强召回，可以增加 LLM Query Rewrite，把用户问题改写成 3 到 5 个不同角度的问题。

### 向量检索

向量检索负责语义相似召回：

```txt
query -> Qwen/Qwen3-Embedding-8B -> pgvector HNSW -> Top K chunks
```

适合：

- 同义表达。
- 语义相关。
- 用户表述和文档表述不完全一致的场景。

短板：

- 对产品型号、数字、缩写、专有名词不够敏感。

### BM25 检索

BM25 负责关键词和精确词召回。

当前实现基于 PostgreSQL 中已落库的 chunk 文本，在 Java 层计算 BM25 分数：

- 英文和数字按连续 token 切分。
- 中文按单字和 bigram 混合切分。
- 支持类似 `RTX 4090`、`M4 Pro`、数字参数、中文关键词的精确召回。

适合：

- 产品型号。
- 编号。
- 数字。
- 专有名词。
- 用户问题和文档存在字面重合的场景。

短板：

- 不理解同义词和深层语义。

### RRF 融合

向量检索和 BM25 的分数不可直接相加，因此使用 RRF（Reciprocal Rank Fusion）按排名融合：

```txt
score = sum(1 / (60 + rank))
```

一个 chunk 如果在多条检索路径中都排名靠前，融合分数会更高。

当前 `/api/vector/search` 返回的 `score` 在 `hybrid` 模式下表示 RRF 融合分，不再是单一路径的 cosine similarity。

## 核心 API

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

检索请求示例：

```json
{
  "knowledgeBaseId": "enterprise-policy",
  "query": "RTX 4090 显卡功耗",
  "topK": 6,
  "retrievalMode": "hybrid",
  "queryExpansionEnabled": true,
  "queryExpansionCount": 4,
  "similarityThreshold": 0.0
}
```

`retrievalMode` 可选：

```txt
vector -> 只走向量检索
bm25   -> 只走 BM25 关键词检索
hybrid -> 多 Query + 向量 + BM25 + RRF，默认
```

说明：

- 正常上传后会自动异步索引。
- `reindex` 用于手动重建某个文档的向量。
- `vector/search` 由问答/Agent 模块调用，用于获取相关 chunk。

## 运行方式

启动基础设施：

```bash
cd knowledge-service
docker compose up -d
```

启动服务：

```bash
cd knowledge-service
mvn spring-boot:run
```

运行测试：

```bash
cd knowledge-service
mvn test
```

## 相关文档

```txt
docs/knowledge-service-architecture.md
docs/chunking-strategies.md
```
