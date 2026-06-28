package com.example.ragagent.config;

import com.example.ragagent.memory.BusinessConversationStateExtractor;
import com.example.ragagent.memory.BusinessLongTermMemoryExtractor;
import com.example.ragagent.memory.ConversationStateExtractor;
import com.example.ragagent.memory.ConversationSummarizer;
import com.example.ragagent.memory.ConversationMemoryService;
import com.example.ragagent.memory.InMemoryConversationMemoryService;
import com.example.ragagent.memory.InMemorySemanticMemoryStore;
import com.example.ragagent.memory.InMemoryUserProfileStore;
import com.example.ragagent.memory.LlmConversationSummarizer;
import com.example.ragagent.memory.LongTermMemoryExtractor;
import com.example.ragagent.memory.PostgresConversationMemoryService;
import com.example.ragagent.memory.RedisConversationMemoryService;
import com.example.ragagent.memory.SemanticMemoryStore;
import com.example.ragagent.memory.UserProfileStore;
import com.example.ragagent.memory.WindowConversationSummarizer;
import com.example.ragagent.service.LlmGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class MemoryServiceConfig {

    @Bean
    public ConversationSummarizer conversationSummarizer(RagProperties properties, LlmGateway llmGateway) {
        ConversationSummarizer fallback = new WindowConversationSummarizer();
        if ("llm".equalsIgnoreCase(properties.memory().summaryMode())) {
            return new LlmConversationSummarizer(llmGateway, fallback);
        }
        return fallback;
    }

    @Bean
    public ConversationStateExtractor conversationStateExtractor() {
        return new BusinessConversationStateExtractor();
    }

    @Bean
    public SemanticMemoryStore semanticMemoryStore() {
        return new InMemorySemanticMemoryStore();
    }

    @Bean
    public UserProfileStore userProfileStore() {
        return new InMemoryUserProfileStore();
    }

    @Bean
    public LongTermMemoryExtractor longTermMemoryExtractor() {
        return new BusinessLongTermMemoryExtractor();
    }

    @Bean
    @ConditionalOnProperty(prefix = "rag.memory", name = "provider", havingValue = "redis")
    public ConversationMemoryService redisMemoryService(
            StringRedisTemplate redisTemplate,
            RagProperties properties,
            ConversationSummarizer conversationSummarizer,
            ConversationStateExtractor conversationStateExtractor,
            SemanticMemoryStore semanticMemoryStore,
            UserProfileStore userProfileStore,
            LongTermMemoryExtractor longTermMemoryExtractor
    ) {
        return new RedisConversationMemoryService(
                redisTemplate,
                properties,
                conversationSummarizer,
                conversationStateExtractor,
                semanticMemoryStore,
                userProfileStore,
                longTermMemoryExtractor
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "rag.memory", name = "provider", havingValue = "postgres")
    public ConversationMemoryService postgresMemoryService(
            JdbcTemplate jdbcTemplate,
            RagProperties properties,
            ConversationSummarizer conversationSummarizer,
            ConversationStateExtractor conversationStateExtractor,
            SemanticMemoryStore semanticMemoryStore,
            UserProfileStore userProfileStore,
            LongTermMemoryExtractor longTermMemoryExtractor
    ) {
        return new PostgresConversationMemoryService(
                jdbcTemplate,
                properties,
                conversationSummarizer,
                conversationStateExtractor,
                semanticMemoryStore,
                userProfileStore,
                longTermMemoryExtractor
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "rag.memory", name = "provider",
            havingValue = "in-memory", matchIfMissing = true)
    public ConversationMemoryService inMemoryMemoryService(
            RagProperties properties,
            ConversationSummarizer conversationSummarizer,
            ConversationStateExtractor conversationStateExtractor,
            SemanticMemoryStore semanticMemoryStore,
            UserProfileStore userProfileStore,
            LongTermMemoryExtractor longTermMemoryExtractor
    ) {
        return new InMemoryConversationMemoryService(
                properties,
                conversationSummarizer,
                conversationStateExtractor,
                semanticMemoryStore,
                userProfileStore,
                longTermMemoryExtractor
        );
    }
}
