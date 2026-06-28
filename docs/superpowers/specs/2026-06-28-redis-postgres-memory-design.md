# Redis / Postgres 持久化对话记忆服务设计

- **日期**: 2026-06-28
- **模块**: `agent-service` 记忆管理
- **状态**: 已批准,待实现
- **关联代码**: `agent-service/src/main/java/com/example/ragagent/memory/`

## 1. 背景与目标

当前 `agent-service` 的对话记忆由 `InMemoryConversationMemoryService` 提供,数据存储在进程内 `ConcurrentHashMap`,存在以下局限:

1. 进程重启即丢失全部会话记忆
2. 无 TTL 管理,长期运行内存膨胀
3. 不支持多实例水平扩展,会话无法跨实例共享

本设计新增 **Redis** 和 **Postgres** 两种持久化实现,通过 `rag.memory.provider` 配置切换,与 `knowledge-service` 已有的 provider 切换模式保持一致。

### 设计决策

| 决策项 | 选择 | 理由 |
|--------|------|------|
| 实现范围 | Redis + Postgres 双实现 | 覆盖高吞吐低延迟(Redis)与持久审计(Postgres)两种场景 |
| 过期策略 | TTL 自动过期,默认 24h | Redis 原生 TTL;Postgres 用 `expires_at` 列 + 定时清理 |
| Postgres 库 | 共享现有 `rag` 库 | 复用 `knowledge-service` 的 Docker Postgres(25432),无需新建数据库 |
| 表管理 | 自动建表 `CREATE TABLE IF NOT EXISTS` | 与 `PgVectorStore` 启动时建表模式一致,不引入 Flyway |
| 数据模型 | 结构化映射(方案 A) | Redis Hash+List 部分更新高效;Postgres 规范化表可 SQL 查询 |

## 2. 整体架构

### 2.1 类层次

```
agent-service/src/main/java/com/example/ragagent/memory/
├── ConversationMemoryService.java          # 接口 (不变)
├── MemoryContext.java                      # record DTO (不变)
├── NoopConversationMemoryService.java      # 保留,降级用
├── AbstractConversationMemoryService.java  # 新增:抽取公共逻辑
├── InMemoryConversationMemoryService.java  # 重构:继承抽象类,去掉 @Service
├── RedisConversationMemoryService.java     # 新增:Redis 实现
└── PostgresConversationMemoryService.java  # 新增:Postgres 实现

agent-service/src/main/java/com/example/ragagent/config/
├── RagProperties.java                      # 扩展 Memory record
└── MemoryServiceConfig.java                # 新增:按 provider 创建 Bean
```

### 2.2 Provider 切换

```yaml
rag:
  memory:
    provider: in-memory    # in-memory | redis | postgres  (默认 in-memory,向后兼容)
    enabled: true
    ttl-seconds: 86400     # 会话过期时间,默认 24h
    recent-messages: 8
    summarize-after-messages: 12
    summary-max-characters: 1600
    state-max-entries: 16
```

`MemoryServiceConfig` 通过 `@ConditionalOnProperty` 按 `provider` 值创建对应 Bean。`provider` 缺失时 `matchIfMissing = true` 创建 `InMemoryConversationMemoryService`,保证现有部署零改动。

### 2.3 接口不变性

`ConversationMemoryService` 接口(`load` / `recordTurn`)和 `MemoryContext` record 完全不变。`ChatOrchestrator`、`MultiAgentOrchestrator`、`PromptBuilder` 零改动。

## 3. AbstractConversationMemoryService — 公共逻辑

### 3.1 模板方法

```java
public abstract class AbstractConversationMemoryService implements ConversationMemoryService {

    @Override
    public final MemoryContext load(ChatRequest request) {
        if (!config.enabled()) return noOpContext(request);
        String convId = normalizeConversationId(request);
        StoredMemory stored = loadStored(convId, request);
        return buildContext(convId, stored);
    }

    @Override
    public final void recordTurn(ChatRequest request, QueryAnalysisResponse analysis, ChatResponse response) {
        if (!config.enabled()) return;
        String convId = normalizeConversationId(request);
        StoredMemory current = loadStored(convId, request);
        StoredMemory updated = applyTurn(current, request, analysis, response);
        persistStored(convId, updated);
    }

    protected abstract StoredMemory loadStored(String conversationId, ChatRequest request);
    protected abstract void persistStored(String conversationId, StoredMemory memory);
}
```

### 3.2 公共逻辑方法

从 `InMemoryConversationMemoryService` 上移:

- `applyTurn(StoredMemory, ChatRequest, QueryAnalysisResponse, ChatResponse)` — 追加消息(去重)+ 摘要刷新 + 状态提取 + LRU 淘汰
- `summarizeOlderMessages(List<ChatMessage>)` — 滚动摘要生成
- `extractOrderId(String query)` — 正则提取订单号
- `extractProduct(String query)` — 正则提取产品名
- `normalizeConversationId(ChatRequest)` — 会话 ID 规范化(自动生成 UUID)
- `normalize(String)` / `trim(String, int)` / `safe(String)` — 文本工具
- `appendIfNew(List<ChatMessage>, ChatMessage)` — 消息去重追加
- `putState(Map, String, String)` / `trimState(Map)` — 状态槽位管理

### 3.3 StoredMemory 中间载体

```java
protected record StoredMemory(
    List<ChatMessage> messages,       // 全量消息列表(非窗口),供摘要计算
    String rollingSummary,
    int summaryVersion,
    Map<String, String> dialogState,
    Instant updatedAt
) {
    // 降级用:从 request.history 构造临时记忆
    public static StoredMemory fromRequest(ChatRequest request) {
        return new StoredMemory(
            request.normalizedHistory(),
            "", 0, Map.of(), Instant.now()
        );
    }
}
```

子类的 `loadStored` 返回 `StoredMemory`(含**全量**消息,非窗口),`persistStored` 接收 `StoredMemory`。`applyTurn` 依赖全量消息计算摘要。

### 3.4 buildContext 方法

```java
protected MemoryContext buildContext(String conversationId, StoredMemory stored) {
    int start = Math.max(0, stored.messages().size() - config.recentMessages());
    return new MemoryContext(
        conversationId,
        stored.messages().subList(start, stored.messages().size()),  // 近期窗口
        stored.rollingSummary(),
        stored.dialogState(),
        stored.messages().size(),   // rawMessageCount = 全量
        stored.summaryVersion()
    );
}
```

`buildContext` 从全量消息中切出近期窗口,与现有 `InMemoryConversationMemoryService.context()` 逻辑一致。

### 3.5 正则常量

```java
protected static final Pattern ORDER_ID = Pattern.compile(
    "(?i)(?:order|\\u8ba2\\u5355|\\u5355\\u53f7)[\\s:#:\\uFF1A-]*([A-Za-z0-9-]{6,})"
);
protected static final Pattern PRODUCT = Pattern.compile(
    "([A-Za-z]+\\s?\\d{1,3}(?:\\s?(?:Pro|Plus|Max|Ultra|Air|Mini))?|...)"
);
```

## 4. RedisConversationMemoryService

### 4.1 Key 布局

```
rag:conv:{conversationId}:meta       Hash    会话元数据 + 摘要
rag:conv:{conversationId}:messages   List    有序消息序列
rag:conv:{conversationId}:state      Hash    对话状态槽位
```

### 4.2 数据结构

**`:meta` Hash**:
| Field | 类型 | 说明 |
|-------|------|------|
| summary | String | 滚动摘要文本 |
| summaryVersion | String | 摘要版本号(数字字符串) |
| messageCount | String | 消息总数 |
| updatedAt | String | ISO-8601 时间戳 |
| knowledgeBaseId | String | 知识库 ID |
| createdAt | String | 会话创建时间 |

**`:messages` List**: 每个元素为 JSON 字符串 `{"role":"user","content":"..."}`,`RPUSH` 追加。

**`:state` Hash**: key-value 槽位,如 `orderId` → `ABC123`。

### 4.3 load() 流程

```
1. EXISTS rag:conv:{id}:meta
   ├─ 不存在:
   │    若 request.normalizedHistory() 非空 → 回填 messages + 写 meta/state + 设 TTL
   │    否则 → 返回空 MemoryContext(仅 conversationId)
   └─ 存在: 继续
2. HGETALL :meta → summary, summaryVersion, messageCount, updatedAt
3. LRANGE :messages -{recentMessages} -1 → 近期窗口(JSON 反序列化为 ChatMessage)
4. HGETALL :state → 状态槽位
5. 组装 StoredMemory → buildContext → 返回 MemoryContext
```

### 4.4 recordTurn() 流程

```
1. 读取当前 StoredMemory (loadStored)
2. applyTurn 计算更新后的 StoredMemory (公共逻辑)
3. 持久化:
   a. 末尾去重检查:LRANGE :messages -1 -1,与待追加消息比较
   b. RPUSH :messages 新消息 (0~2 条)
   c. HSET :meta summary / summaryVersion / messageCount / updatedAt
   d. state Hash 全量重写:DEL :state + HSET :state 各槽位 (LRU 淘汰后)
   e. EXPIRE 三个 key {ttl-seconds} (续期)
```

### 4.5 序列化

- 消息用 Jackson `ObjectMapper` 序列化为 JSON 字符串,`StringRedisTemplate` 操作
- Hash field 值统一为字符串
- 状态 Hash 全量重写策略:先 `DEL :state` 再 `HSET`,避免删除被 LRU 淘汰的 key 的复杂性

### 4.6 TTL 管理

- 首次 `load()` 创建会话时设 TTL
- 每次 `recordTurn()` 对三个 key 执行 `EXPIRE` 续期
- 活跃会话不过期,空闲会话 24h 后自动清除

## 5. PostgresConversationMemoryService

### 5.1 表结构

```sql
CREATE TABLE IF NOT EXISTS conversation_memory (
    id               VARCHAR(128)  PRIMARY KEY,
    summary          TEXT          NOT NULL DEFAULT '',
    summary_version  INT           NOT NULL DEFAULT 0,
    message_count    INT           NOT NULL DEFAULT 0,
    dialog_state     JSONB         NOT NULL DEFAULT '{}',
    knowledge_base_id VARCHAR(64),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ   NOT NULL
);

CREATE TABLE IF NOT EXISTS conversation_messages (
    id               BIGSERIAL     PRIMARY KEY,
    conversation_id  VARCHAR(128)  NOT NULL REFERENCES conversation_memory(id) ON DELETE CASCADE,
    seq              INT           NOT NULL,
    role             VARCHAR(32)   NOT NULL,
    content          TEXT          NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE(conversation_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_conv_msg_id_seq
    ON conversation_messages(conversation_id, seq);
```

### 5.2 建表与清理

```java
@PostConstruct
void initializeSchema() {
    jdbcTemplate.execute(CREATE_MEMORY_TABLE_SQL);
    jdbcTemplate.execute(CREATE_MESSAGES_TABLE_SQL);
    jdbcTemplate.execute(CREATE_INDEX_SQL);
    cleanExpiredConversations();  // 启动时清理上次停机期间过期数据
}

@Scheduled(fixedDelayString = "PT1H")
void cleanExpiredConversations() {
    int deleted = jdbcTemplate.update(
        "DELETE FROM conversation_memory WHERE expires_at < now()");
    if (deleted > 0) {
        log.info("Cleaned expired conversations count={}", deleted);
    }
    // conversation_messages 通过 ON DELETE CASCADE 自动删除
}
```

需在主应用类或 Config 上添加 `@EnableScheduling`。

### 5.3 load() SQL

```sql
-- 读 meta(含过期过滤)
SELECT summary, summary_version, message_count, dialog_state, knowledge_base_id
FROM conversation_memory
WHERE id = ? AND expires_at > now();

-- 读近期消息窗口
SELECT role, content FROM conversation_messages
WHERE conversation_id = ?
ORDER BY seq DESC
LIMIT ?;
-- Java 端反转回正序
```

会话不存在时初始化:

```sql
INSERT INTO conversation_memory (id, expires_at)
VALUES (?, now() + interval '? seconds')
ON CONFLICT (id) DO NOTHING;
```

若 request 携带 history,批量插入消息:

```sql
INSERT INTO conversation_messages (conversation_id, seq, role, content)
VALUES (?, 0, ?, ?), (?, 1, ?, ?), ...
ON CONFLICT (conversation_id, seq) DO NOTHING;
```

### 5.4 recordTurn() SQL

```sql
-- 取当前 max seq
SELECT COALESCE(MAX(seq), -1) FROM conversation_messages WHERE conversation_id = ?;

-- 去重检查:比较末尾消息
SELECT role, content FROM conversation_messages
WHERE conversation_id = ? ORDER BY seq DESC LIMIT 1;

-- 插入新消息
INSERT INTO conversation_messages (conversation_id, seq, role, content)
VALUES (?, ?, ?, ?);

-- 读全量消息计算摘要(Java 端)
SELECT role, content FROM conversation_messages
WHERE conversation_id = ? ORDER BY seq;

-- 更新 meta + 续期
UPDATE conversation_memory
SET summary = ?, summary_version = ?, message_count = ?,
    dialog_state = ?::jsonb, updated_at = now(),
    expires_at = now() + interval '? seconds'
WHERE id = ?;
```

### 5.5 设计要点

- **ON DELETE CASCADE**:删会话自动删消息,无需应用层级联
- **UNIQUE(conversation_id, seq)**:幂等防重,`ON CONFLICT DO NOTHING` 兼容重试
- **dialog_state JSONB**:动态 key-value 天然适配,支持 `WHERE dialog_state->>'orderId' = 'ABC'` 调试
- **消息窗口读取**:`ORDER BY seq DESC LIMIT N` + Java 反转,比 `OFFSET` 高效

## 6. 配置与依赖变更

### 6.1 pom.xml 新增依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

### 6.2 RagProperties.Memory 扩展

新增两个字段:
- `provider`: `in-memory` | `redis` | `postgres`,默认 `in-memory`
- `ttlSeconds`: 会话 TTL 秒数,默认 86400(24h),范围 [60, 604800]

校验逻辑:
```java
provider = (provider == null || provider.isBlank()) ? "in-memory" : provider;
ttlSeconds = ttlSeconds == null ? 86400L : Math.max(60L, Math.min(ttlSeconds, 604800L));
```

### 6.3 application.yml 新增

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:25432/rag
    username: rag
    password: rag
    driver-class-name: org.postgresql.Driver
  data:
    redis:
      host: localhost
      port: 26380

rag:
  memory:
    provider: in-memory
    ttl-seconds: 86400
```

### 6.4 MemoryServiceConfig

```java
@Configuration
public class MemoryServiceConfig {

    @Bean
    @ConditionalOnProperty(prefix = "rag.memory", name = "provider", havingValue = "redis")
    public ConversationMemoryService redisMemoryService(
            StringRedisTemplate redisTemplate, RagProperties properties) {
        return new RedisConversationMemoryService(redisTemplate, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "rag.memory", name = "provider", havingValue = "postgres")
    public ConversationMemoryService postgresMemoryService(
            JdbcTemplate jdbcTemplate, RagProperties properties) {
        return new PostgresConversationMemoryService(jdbcTemplate, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "rag.memory", name = "provider",
            havingValue = "in-memory", matchIfMissing = true)
    public ConversationMemoryService inMemoryMemoryService(RagProperties properties) {
        return new InMemoryConversationMemoryService(properties);
    }
}
```

`InMemoryConversationMemoryService` 去掉 `@Service` 注解,改由 Config 创建。

## 7. 错误处理与降级

### 7.1 存储层异常

```java
@Override
protected StoredMemory loadStored(String conversationId, ChatRequest request) {
    try {
        return doLoad(conversationId, request);
    } catch (Exception ex) {
        log.warn("Memory load failed, falling back to request history. conversation={} error={}",
                 conversationId, ex.getMessage());
        return StoredMemory.fromRequest(request);  // 降级:用 request 历史
    }
}

@Override
protected void persistStored(String conversationId, StoredMemory memory) {
    try {
        doPersist(conversationId, memory);
    } catch (Exception ex) {
        log.warn("Memory persist failed, ignoring. conversation={} error={}",
                 conversationId, ex.getMessage());
        // 静默丢弃,不抛异常,不影响主流程
    }
}
```

**原则**:记忆层故障不应阻断对话主流程。`load` 降级为 request 历史,`persist` 静默丢弃。

### 7.2 连接初始化容错

- `@PostConstruct` 建表失败时仅 `log.warn`,不抛异常(DB 可能未启动,后续请求降级)
- Redis 连接异常由 `RedisTemplate` 包装为 `RedisConnectionFailureException`,被通用 catch 捕获

## 8. 测试策略

### 8.1 公共逻辑测试 — `AbstractConversationMemoryServiceTests`

用匿名子类 mock `loadStored`/`persistStored`,纯逻辑测试:
- `shouldSummarizeWhenThresholdReached`
- `shouldExtractOrderIdFromChineseQuery`
- `shouldTrimStateByLruWhenExceedsMaxEntries`
- `shouldDeduplicateConsecutiveSameMessages`

### 8.2 Redis 实现测试 — `RedisConversationMemoryServiceTests`

依赖 Docker Redis(26380):
- `shouldPersistAndLoadRoundTrip`
- `shouldExpireAfterTtl`
- `shouldHandleNonexistentConversation`
- `shouldBackfillFromRequestHistory`

### 8.3 Postgres 实现测试 — `PostgresConversationMemoryServiceTests`

依赖 Docker Postgres(25432):
- `shouldCreateTablesOnStartup`
- `shouldPersistMessagesInOrder`
- `shouldExpireConversationsAfterTtl`
- `shouldCascadeDeleteMessagesWhenConversationDeleted`

### 8.4 现有测试回归

- `ChatOrchestratorTests` 显式构造 `InMemoryConversationMemoryService` — 不受影响
- 默认 `provider=in-memory` 时所有现有测试通过

### 8.5 测试基础设施

- 使用项目已有的 Docker Redis/Postgres(26380/25432)
- 不引入 Testcontainers(项目当前未使用)
- Redis/Postgres 测试类用 `@EnabledIfSystemProperty` 守护,环境就绪时才跑

## 9. 过期清理与可观测性

### 9.1 Postgres 定时清理

`@Scheduled(fixedDelayString = "PT1H")` 每小时清理 `expires_at < now()` 的会话。`conversation_messages` 通过 `ON DELETE CASCADE` 自动删除。

### 9.2 Redis

TTL 由 Redis 原生管理,无需应用层清理。

### 9.3 可观测性

`load` 和 `recordTurn` 添加 `log.debug` 输出 conversationId、messageCount、summaryVersion、stateKeys 数量。`ChatOrchestrator` 现有日志(`memoryMessages`、`summaryVersion`)自动反映新实现数据,无需改动。

## 10. 实现顺序

1. 扩展 `RagProperties.Memory`(provider, ttlSeconds)
2. 新增 `AbstractConversationMemoryService`,从 `InMemoryConversationMemoryService` 抽取公共逻辑
3. 重构 `InMemoryConversationMemoryService` 继承抽象类,去掉 `@Service`
4. 新增 `MemoryServiceConfig`,三个 `@ConditionalOnProperty` Bean
5. 新增 `RedisConversationMemoryService`
6. 新增 `PostgresConversationMemoryService`
7. 更新 `pom.xml` 依赖、`application.yml` 配置
8. 添加 `@EnableScheduling`
9. 编写测试
10. 运行 `mvn test` 验证回归

## 11. 不在本次范围内

- 多实例会话亲和性(Sticky session) — 由前端/网关层处理
- 会话导出/迁移工具
- 记忆压缩的 LLM 摘要(当前为规则式拼接,未来可扩展)
- Redis 集群/Sentinel 配置 — 当前单节点足够
- Postgres 分区表 — 会话量不大时无需
