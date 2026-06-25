---
title: "向量索引与近似最近邻：HNSW/IVF/PQ 原理与 pgvector 实践"
description: "系统讲解 ANN 检索的三大主流算法族（HNSW/IVF/PQ）、组合索引、参数调优与召回率评估，并落地 pgvector 在本项目中的实践"
category: "嵌入与向量化"
subcategory: "向量索引"
tags: [向量索引, ANN, HNSW, IVF, PQ, pgvector]
keywords: [ANN, 近似最近邻, HNSW, IVF, PQ, pgvector, 向量数据库]
difficulty: "专家"
audience: "基础设施工程师、算法工程师"
related_topics: ["文本嵌入", "稠密向量检索", "混合检索"]
author: "RAG Knowledge Base"
source: "internal-knowledge-base"
language: "zh-CN"
created: "2026-06-24"
updated: "2026-06-24"
version: "1.0"
---

# 向量索引与近似最近邻：HNSW/IVF/PQ 原理与 pgvector 实践

在稠密检索驱动的 RAG 系统中，检索阶段的核心计算是：给定查询向量 $q \in \mathbb{R}^d$，从规模为 $N$ 的向量集合 $\{x_1, \dots, x_N\}$ 中找出与 $q$ 最相似的若干向量。相似度通常由内积、余弦距离或 L2 距离衡量。这一问题的工程难点不在于"如何计算距离"，而在于"如何在大规模数据下避免线性扫描"。本文系统梳理精确最近邻的不可扩展性、ANN 的召回率-速度权衡、三大主流算法族（HNSW / IVF / PQ）及其组合形态，并落到 pgvector 在本项目的实践。

## 一、精确最近邻的不可扩展性

精确最近邻（Exact Nearest Neighbor, NN）要求返回全局最优的 Top-K 结果。最朴素的实现是暴力扫描（brute-force / flat scan），对每个库向量计算与 $q$ 的距离，再做部分排序。其时间复杂度为 $O(N \cdot d)$，与数据规模线性相关。

在 RAG 场景下，$d$ 通常为 768（如 BGE-base）或 1536（如 OpenAI text-embedding-3-small），$N$ 则可能从百万级增长到亿级。当 $N = 10^7$、$d = 768$ 时，单次查询的浮点运算量约为 $7.68 \times 10^9$ FLOPs，即便使用 SIMD 也难以满足 P99 < 50ms 的在线延迟约束。更关键的是，精确 NN 不存在次线性查询算法（在任意分布下），已有的次线性方法（如 KD-Tree、球树）在高维下会遭遇"维度灾难"——其剪枝效果随维度上升急剧退化，最终退化为接近全扫描。

因此业界普遍转向近似最近邻（Approximate Nearest Neighbor, ANN）：以可控的精度损失换取数量级的速度提升。

## 二、ANN 的召回率-速度权衡

ANN 的本质是放弃"保证全局最优"，转而"以高概率找到近邻"。其核心评价指标是一对矛盾：

- **Recall@K**：返回的 Top-K 中包含真实 Top-K 的比例，衡量精度。
- **QPS（Queries Per Second）**：单位时间可处理的查询数，衡量吞吐。

两者通常呈现明显的 Parefront：提高召回率往往需要扩大搜索范围，从而降低 QPS。好的索引算法应在这条前沿曲线上尽量外推——即在相同召回率下达到更高 QPS，或在相同 QPS 下达到更高召回率。算法选择与参数调优的最终目标，是在业务可接受的召回下限（如 Recall@10 ≥ 0.95）处最大化 QPS。

需要强调，召回率评估必须在真实业务数据分布上进行。公开 benchmark（如 ann-benchmarks）只能提供相对参考，嵌入维度、数据几何结构、距离度量都会显著影响各算法的实际表现。

## 三、HNSW：分层小世界图

HNSW（Hierarchical Navigable Small World）是当前最广泛使用的图索引算法，其核心思想是用多层近邻图实现"从粗到细"的导航，类比跳表（skip list）的空间结构。

### 3.1 结构

HNSW 维护 $L$ 层图，第 $l$ 层包含的节点数随 $l$ 增大而指数衰减。最底层（layer 0）包含全部节点，连接密度最高；上层节点稀疏，长程连接多。查询从最顶层入口节点出发，在每层贪心地向更近的邻居移动，到达该层局部最优点后下沉到下一层，最终在 layer 0 做精细搜索。

### 3.2 关键参数

- **$M$**：每个节点在每层（非底层）的最大连接数。底层连接数为 $M_{\max 0} = 2M$。$M$ 越大，图的连通性越好、召回越高，但内存与构建成本上升。典型取值 16–48。
- **$ef_\text{construction}$**：构建时动态候选列表大小，影响图质量。通常设为 $M$ 的 5–10 倍或更高。
- **$ef_\text{search}$**：查询时动态候选列表大小，是召回与速度的主要旋钮。$ef$ 越大召回越高、速度越慢。

查询复杂度近似为 $O(\log N \cdot ef)$，远优于线性扫描。

### 3.3 构建与查询

构建时，对新节点 $x$，先用指数衰减分布采样其最高层级 $l$；从顶层入口贪心下降到 $l+1$ 层；在 $l$ 层及以下，以 $ef_\text{construction}$ 为候选宽度搜索近邻，按启发式（偏向多样化、避免聚集）选择 $M$ 个连接。查询流程类似，只是搜索宽度切换为 $ef_\text{search}$，并在 layer 0 返回 Top-K。

HNSW 的优势在于查询延迟低、召回曲线陡峭；代价是内存占用大（需存储图结构 + 原始向量），且增量插入的写放大较明显。

## 四、IVF：倒排+聚类

IVF（Inverted File）借鉴文本检索的倒排思想：先用聚类将空间划分为若干"桶"（cluster / cell），每个桶维护落入其中的向量列表；查询时只搜索最近的若干桶。

### 4.1 构建

用 k-means（通常用 IVF 的改进版 IVF-Flat 或 FAISS 的 IVF）在训练集上得到 $n_\text{list}$ 个聚类中心。每个库向量被分配到最近的中心，构成 $n_\text{list}$ 个倒排链表。查询时计算 $q$ 到所有中心的距离，选取最近的 $n_\text{probe}$ 个桶，仅在这些桶内做精确扫描。

### 4.2 关键参数

- **$n_\text{list}$**（聚类数）：粗量化粒度。一般经验值 $n_\text{list} \approx \sqrt{N}$ 至 $O(N)$。过小则每桶过大、扫描重；过大则训练成本高、桶内样本稀疏。
- **$n_\text{probe}$**（探测桶数）：召回与速度的主旋钮。$n_\text{probe}$ 越大召回越高、越慢。

理想情况下，每桶平均含 $N / n_\text{list}$ 个向量，查询扫描量约为 $n_\text{probe} \cdot N / n_\text{list}$。当 $n_\text{probe} / n_\text{list} = 0.05$ 时，可覆盖约 95% 的目标近邻（取决于数据分布与边界效应）。

IVF 的优势是构建快、内存可控、支持批量训练；缺点是查询时需遍历多个桶、对数据分布敏感（簇边界处的近邻易漏）。常见的改进包括 IVF-Flat（桶内存原始向量）与 IVF-PQ（桶内做乘积量化压缩）。

## 五、PQ/SQ：乘积量化与压缩

PQ（Product Quantization）用于将高维浮点向量压缩为短编码，显著降低内存与 I/O，代价是引入量化误差。

### 5.1 原理

将 $d$ 维向量切分为 $m$ 个子空间，每个子空间维度 $d' = d / m$。对每个子空间独立做 k-means（聚类数 $k = 2^b$，通常 $b=8$ 即 256 个码字），得到 $m$ 个码本。原向量被编码为 $m$ 个码字索引（每个 $b$ bit），总编码长度 $m \cdot b$ bit。例如 $d=768$、$m=64$、$b=8$，原始 3072 字节压缩为 64 字节，压缩比约 48x。

距离计算使用预计算的查找表（Asymmetric Distance Computation, ADC）：对查询 $q$ 预先算出其每个子向量到该子空间所有码字的距离，存为 $m \times k$ 的表；库向量只需用其 $m$ 个码字索引查表累加即可得到近似距离，计算量为 $m$ 次加法，极快。

### 5.2 关键参数

- **$m$**（子空间数）：决定压缩率与精度。$m$ 越大精度越高、压缩率越低。
- **$k=2^b$**（每子空间码字数）：$b$ 通常取 8。$b$ 越大码本越细但训练与表存储成本上升。

### 5.3 SQ 与对比

标量量化（Scalar Quantization, SQ）对每个维度独立做线性量化（如 float32 → int8），实现简单、误差均匀，但压缩率与精度通常不如 PQ。PQ 通过子空间分解捕获维度间相关性，在相同比特预算下精度更高，但训练成本更高。实践中常先用 PQ/SQ 压缩，再配合 IVF 或 HNSW 加速。

## 六、组合索引

单一算法各有短板，工业界广泛使用组合形态：

- **IVF + PQ**（如 FAISS IVFPQ）：用 IVF 粗筛桶，桶内向量用 PQ 压缩并做 ADC 快速距离计算。兼顾速度与内存，适合亿级数据。
- **HNSW + PQ**（如 FAISS HNSW32+PQ 或 IVF-HNSW）：用 HNSW 在 PQ 编码上导航，或在聚类中心上建 HNSW（IVF-HNSW）加速 $n_\text{probe}$ 选择。延迟更低，但实现复杂、构建慢。
- **HNSW + SQ**：图结构保持原始向量用于重排（re-rank），桶内/节点用 SQ 压缩，兼顾质量与内存。

组合索引通常引入"两阶段"思路：第一阶段用压缩向量快速取候选集（如 Top-200），第二阶段用原始向量对候选集精确重排得到最终 Top-K。重排能显著拉高召回，是落地时性价比最高的优化之一。

## 七、算法对比

| 算法 | 核心结构 | 查询复杂度 | 内存 | 增量友好度 | 典型适用规模 |
|---|---|---|---|---|---|
| Flat | 无索引 | $O(Nd)$ | 低 | 极好 | 小规模 / 高精度基准 |
| IVF-Flat | 倒排+聚类 | $O(n_\text{probe} \cdot N/n_\text{list} \cdot d)$ | 中 | 好 | 百万–千万 |
| IVF-PQ | 倒排+PQ | $O(n_\text{probe} \cdot N/n_\text{list} \cdot m)$ | 低 | 好 | 千万–亿 |
| HNSW | 分层近邻图 | $O(\log N \cdot ef)$ | 高 | 一般 | 百万–千万 |
| HNSW+PQ | 图+压缩 | $O(\log N \cdot ef \cdot m)$ | 中 | 一般 | 千万级 |

## 八、参数调优要点

- **先定召回下限，再压 QPS**：以业务可接受的 Recall@K 为锚点，在该点比较各算法。
- **HNSW**：$M$ 决定质量上限，$ef_\text{search}$ 决定在线权衡。先调 $M$ 到召回饱和，再降 $ef_\text{search}$ 至刚好满足召回。
- **IVF**：$n_\text{list}$ 一经训练较难频繁调整，按 $\sqrt{N}$ 起点；在线主要调 $n_\text{probe}$。
- **PQ**：$m$ 选择需为 $d$ 的因子，常用 $m \in \{32, 64, 96\}$；若内存充足可提高 $m$ 或退回 SQ。
- **重排**：始终优先加入重排阶段，成本极低、收益稳定。

## 九、召回率评估：Recall@K vs QPS

评估流程：先离线构造 ground-truth（对测试集用暴力扫描得到真实 Top-K），再在不同参数下统计返回集合与真实集合的交集占比，绘制 Recall@K 随 QPS 变化的曲线。注意：评估必须与生产同构——相同的距离度量、相同的嵌入模型、相似的查询分布。常见陷阱包括训练/查询分布漂移、距离度量不一致（内积 vs L2 vs 余弦）、以及忽略归一化（余弦距离下应预先 L2 归一化向量）。曲线的"拐点"通常是性价比最优的工作点。

## 十、本项目落地：pgvector 实践

本项目的 storage-layer 采用 PostgreSQL + pgvector 存储与检索向量。`PgVectorStore` 负责向量持久化与 ANN 查询，支持 HNSW 索引；另有 `MemoryVectorStore` 作为测试与降级路径，在无数据库环境或单元测试时以内存暴力扫描替代。生产链路只走 `PgVectorStore`，确保检索质量与持久性。

### 10.1 建表与向量列声明

pgvector 通过 `vector` 类型声明向量列，维度在建表时固定：

```sql
CREATE TABLE IF NOT EXISTS document_chunks (
    id          UUID PRIMARY KEY,
    doc_id      UUID NOT NULL,
    chunk_text  TEXT NOT NULL,
    embedding   vector(1024) NOT NULL
);
```

`vector(1024)` 表示 1024 维向量，需与嵌入模型输出维度一致。若使用余弦距离，建议在写入前对向量做 L2 归一化，或依赖查询算子处理。

### 10.2 建 HNSW 索引

```sql
CREATE INDEX IF NOT EXISTS idx_doc_chunks_embedding_hnsw
ON document_chunks
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);
```

- `vector_cosine_ops` 指定余弦距离算子族（余弦场景需向量已归一化，等价于内积）。
- `m` 对应上文 $M$，`ef_construction` 对应 $ef_\text{construction}$。
- 查询时通过 `SET hnsw.ef_search = 100;` 调节 $ef_\text{search}$，可在会话级动态调整，无需重建索引。

pgvector 也支持 IVFFlat 索引（`USING ivfflat`，参数 `lists` / `probes`），用于内存敏感或增量写入频繁的场景；HNSW 在召回与延迟上通常更优，本项目默认采用 HNSW。

### 10.3 查询算子

余弦距离查询使用 `<=>` 算子（返回距离，越小越相似），结合 `ORDER BY ... LIMIT k` 完成 ANN：

```sql
SELECT id, chunk_text, embedding <=> :query_vec AS distance
FROM document_chunks
ORDER BY embedding <=> :query_vec
LIMIT 10;
```

`<=>` 为余弦距离；`<->` 为 L2 距离；`<#>` 为负内积。三者分别对应 `vector_cosine_ops`、`vector_l2_ops`、`vector_ip_ops` 算子族，索引必须与查询算子匹配，否则索引不会被使用（退化为顺序扫描）。

`PgVectorStore` 在内部将查询嵌入序列化为 pgvector 文本格式、设置 `hnsw.ef_search`、执行上述 SQL 并解析返回行。当数据库不可用或处于测试上下文时，链路降级到 `MemoryVectorStore`，在内存中做暴力余弦扫描，保证功能可用但牺牲规模与性能。

## 小结与参数建议

向量检索的核心是在召回率与速度之间寻找业务最优的工作点。HNSW 以低延迟、高召回见长，适合千万级以内的在线检索；IVF 以低内存、易扩展见长，适合更大规模或写入密集场景；PQ/SQ 提供压缩层，是规模化的必备手段。组合索引与重排是工业落地的常见组合。

针对本项目的 pgvector + HNSW 链路，参数建议如下：

- 起步配置：$M = 16$、$ef_\text{construction} = 64$、$ef_\text{search} = 100$。
- 若召回不足，优先提高 `ef_search`（低成本、可热调）；若仍不足再考虑重建时提高 $M$。
- 若 QPS 不达标，先降 `ef_search` 至召回下限；必要时引入重排（取 Top-200 重排为 Top-10）。
- 始终确保查询算子与索引算子族一致，向量在余弦场景下预先归一化。
- 大规模数据（> 千万）评估 IVFFlat 或上送专用向量库（如 FAISS/Milvus），保留 `MemoryVectorStore` 仅用于测试降级。

最后，召回率评估应纳入 CI：每次嵌入模型或索引参数变更后，跑一组固定 ground-truth 的 Recall@K vs QPS 基准，防止回归静默发生。
