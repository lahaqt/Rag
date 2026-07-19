package com.example.ragagent.memory;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatMessage;
import com.example.ragagent.dto.ChatRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisConversationMemoryService extends AbstractConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(RedisConversationMemoryService.class);
    private static final String KEY_PREFIX = "rag:conv:";
    private static final String META_SUFFIX = ":meta";
    private static final String MESSAGES_SUFFIX = ":messages";
    private static final String STATE_SUFFIX = ":state";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisConversationMemoryService(StringRedisTemplate redisTemplate, RagProperties properties) {
        super(properties.memory());
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    public RedisConversationMemoryService(
            StringRedisTemplate redisTemplate,
            RagProperties properties,
            ConversationSummarizer summarizer,
            ConversationStateExtractor stateExtractor,
            SemanticMemoryStore semanticMemoryStore,
            UserProfileStore userProfileStore,
            LongTermMemoryExtractor longTermMemoryExtractor
    ) {
        super(
                properties.memory(),
                summarizer,
                stateExtractor,
                semanticMemoryStore,
                userProfileStore,
                longTermMemoryExtractor
        );
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    protected StoredMemory loadStored(String conversationId, ChatRequest request) {
        try {
            return doLoad(conversationId, request);
        } catch (Exception ex) {
            log.warn("Redis memory load failed, falling back to request history. conversation={} error={}",
                    conversationId, ex.getMessage());
            return StoredMemory.fromRequest(request);
        }
    }

    private StoredMemory doLoad(String conversationId, ChatRequest request) {
        String metaKey = metaKey(conversationId);
        String messagesKey = messagesKey(conversationId);
        String stateKey = stateKey(conversationId);

        Boolean metaExists = redisTemplate.hasKey(metaKey);
        if (Boolean.FALSE.equals(metaExists) || metaExists == null) {
            if (request.normalizedHistory().isEmpty()) {
                return new StoredMemory(List.of(), "", 0, 0, Map.of(), Map.of(), Instant.now());
            }
            StoredMemory seed = StoredMemory.fromRequest(request);
            seedConversation(conversationId, seed);
            return seed;
        }

        Map<Object, Object> metaMap = redisTemplate.opsForHash().entries(metaKey);
        String summary = getStr(metaMap, "summary");
        int summaryVersion = getInt(metaMap, "summaryVersion");
        int summarizedMessageCount = getInt(metaMap, "summarizedMessageCount");
        Map<String, TurnSummary> turnSummaries = deserializeTurnSummaries(getStr(metaMap, "turnSummaries"));
        Instant updatedAt = Instant.parse(getStr(metaMap, "updatedAt"));

        List<ChatMessage> allMessages = readAllMessages(messagesKey);
        Map<String, String> dialogState = readState(stateKey);

        return new StoredMemory(
                allMessages,
                summary,
                summaryVersion,
                summarizedMessageCount,
                dialogState,
                turnSummaries,
                updatedAt
        );
    }

    @Override
    protected void persistStored(String conversationId, StoredMemory memory) {
        try {
            doPersist(conversationId, memory);
        } catch (Exception ex) {
            log.warn("Redis memory persist failed. conversation={} error={}",
                    conversationId, ex.getMessage());
            throw new IllegalStateException("Redis memory persist failed.", ex);
        }
    }

    @Override
    protected void deleteStored(String storageKey) {
        try {
            redisTemplate.delete(List.of(metaKey(storageKey), messagesKey(storageKey), stateKey(storageKey)));
        } catch (Exception exception) {
            log.warn("Redis memory delete failed. conversation={} error={}", storageKey, exception.getMessage());
        }
    }

    private void doPersist(String conversationId, StoredMemory memory) {
        String metaKey = metaKey(conversationId);
        String messagesKey = messagesKey(conversationId);
        String stateKey = stateKey(conversationId);

        redisTemplate.delete(messagesKey);
        for (ChatMessage message : memory.messages()) {
            redisTemplate.opsForList().rightPush(messagesKey, serializeMessage(message));
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("summary", memory.rollingSummary());
        meta.put("summaryVersion", Integer.toString(memory.summaryVersion()));
        meta.put("summarizedMessageCount", Integer.toString(memory.summarizedMessageCount()));
        meta.put("messageCount", Integer.toString(memory.summarizedMessageCount() + memory.messages().size()));
        meta.put("turnSummaries", serializeTurnSummaries(memory.turnSummaries()));
        meta.put("updatedAt", memory.updatedAt().toString());
        redisTemplate.opsForHash().putAll(metaKey, meta);

        redisTemplate.delete(stateKey);
        if (!memory.dialogState().isEmpty()) {
            Map<String, String> stateMap = new LinkedHashMap<>(memory.dialogState());
            redisTemplate.opsForHash().putAll(stateKey, stateMap);
        }

        long ttl = config.ttlSeconds();
        redisTemplate.expire(metaKey, java.time.Duration.ofSeconds(ttl));
        redisTemplate.expire(messagesKey, java.time.Duration.ofSeconds(ttl));
        redisTemplate.expire(stateKey, java.time.Duration.ofSeconds(ttl));
    }

    private void seedConversation(String conversationId, StoredMemory seed) {
        String metaKey = metaKey(conversationId);
        String messagesKey = messagesKey(conversationId);
        String stateKey = stateKey(conversationId);

        for (ChatMessage message : seed.messages()) {
            redisTemplate.opsForList().rightPush(messagesKey, serializeMessage(message));
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("summary", seed.rollingSummary());
        meta.put("summaryVersion", Integer.toString(seed.summaryVersion()));
        meta.put("summarizedMessageCount", Integer.toString(seed.summarizedMessageCount()));
        meta.put("messageCount", Integer.toString(seed.messages().size()));
        meta.put("turnSummaries", serializeTurnSummaries(seed.turnSummaries()));
        meta.put("updatedAt", seed.updatedAt().toString());
        redisTemplate.opsForHash().putAll(metaKey, meta);

        long ttl = config.ttlSeconds();
        redisTemplate.expire(metaKey, java.time.Duration.ofSeconds(ttl));
        redisTemplate.expire(messagesKey, java.time.Duration.ofSeconds(ttl));
        redisTemplate.expire(stateKey, java.time.Duration.ofSeconds(ttl));
    }

    private List<ChatMessage> readAllMessages(String messagesKey) {
        List<String> raw = redisTemplate.opsForList().range(messagesKey, 0, -1);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> messages = new ArrayList<>(raw.size());
        for (String json : raw) {
            messages.add(deserializeMessage(json));
        }
        return messages;
    }

    private Map<String, String> readState(String stateKey) {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(stateKey);
        if (raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> state = new LinkedHashMap<>(raw.size());
        raw.forEach((k, v) -> state.put(k.toString(), v.toString()));
        return state;
    }

    private String serializeMessage(ChatMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize ChatMessage", e);
        }
    }

    private ChatMessage deserializeMessage(String json) {
        try {
            return objectMapper.readValue(json, ChatMessage.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize ChatMessage", e);
        }
    }

    private String serializeTurnSummaries(Map<String, TurnSummary> turnSummaries) {
        try {
            return objectMapper.writeValueAsString(turnSummaries);
        } catch (JsonProcessingException exception) {
            throw new RuntimeException("Failed to serialize turn summaries", exception);
        }
    }

    private Map<String, TurnSummary> deserializeTurnSummaries(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, TurnSummary>>() { });
        } catch (JsonProcessingException exception) {
            log.warn("Failed to deserialize turn summaries. error={}", exception.getMessage());
            return Map.of();
        }
    }

    private String getStr(Map<Object, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : value.toString();
    }

    private int getInt(Map<Object, Object> map, String key) {
        String value = getStr(map, key);
        return value.isBlank() ? 0 : Integer.parseInt(value);
    }

    private String metaKey(String conversationId) {
        return KEY_PREFIX + conversationId + META_SUFFIX;
    }

    private String messagesKey(String conversationId) {
        return KEY_PREFIX + conversationId + MESSAGES_SUFFIX;
    }

    private String stateKey(String conversationId) {
        return KEY_PREFIX + conversationId + STATE_SUFFIX;
    }
}
