package com.example.ragagent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatMessage;
import com.example.ragagent.dto.ChatOptions;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.service.LlmGateway;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class ConversationMemoryServiceTests {

    @Test
    void separatesAnalysisPromptAndStateViews() {
        MemoryContext context = new MemoryContext(
                "session-1",
                List.of(new ChatMessage("user", "latest question")),
                "older conversation summary",
                Map.of("orderId", "ABC123456"),
                5,
                1
        );

        assertThat(context.analysisMemory().messages())
                .extracting(ChatMessage::role)
                .containsExactly("user");

        assertThat(context.promptMemory().messages())
                .extracting(ChatMessage::role)
                .containsExactly("memory_summary", "memory_state", "user");

        assertThat(context.stateMemory().values())
                .containsEntry("orderId", "ABC123456");
    }

    @Test
    void appliesConfiguredSummarizerAndStateExtractor() {
        RagProperties properties = new RagProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                memory(20, 1, 20, 16)
        );
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(
                properties,
                (currentSummary, messages, maxTokens) -> new MemorySummary("custom-summary", true),
                (currentState, request, analysis, response, messageCount, maxEntries) -> Map.of(
                        "customState", "enabled",
                        "messageCount", Integer.toString(messageCount)
                )
        );

        for (int i = 1; i <= 2; i++) {
            ChatRequest request = request("question " + i);
            memoryService.recordTurn(request, analysis(request), response("answer " + i));
        }

        MemoryContext context = memoryService.load(request("question 3"));

        assertThat(context.rollingSummary()).isEqualTo("custom-summary");
        assertThat(context.summaryVersion()).isEqualTo(1);
        assertThat(context.stateMemory().values())
                .containsEntry("customState", "enabled")
                .containsEntry("messageCount", "4");
        assertThat(context.promptMemory().messages())
                .extracting(ChatMessage::content)
                .anyMatch(content -> content.contains("custom-summary"));
    }

    @Test
    void keepsUnconfirmedPreferenceOutOfPromptMemory() {
        RagProperties properties = new RagProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new RagProperties.Memory("in-memory", true, 200000, 32000, 4000, 16, 86400L, "window", 4, true)
        );
        InMemorySemanticMemoryStore semanticStore = new InMemorySemanticMemoryStore();
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(
                properties,
                new WindowConversationSummarizer(),
                new BusinessConversationStateExtractor(),
                semanticStore,
                new InMemoryUserProfileStore(),
                new BusinessLongTermMemoryExtractor()
        );

        ChatRequest first = userRequest("I prefer concise answers.", "user-1");
        memoryService.recordTurn(first, analysis(first), response("Understood."));

        ChatRequest followUp = userRequest("Please answer concise.", "user-1");
        MemoryContext context = memoryService.loadWorkingContext(followUp);

        assertThat(context.analysisMemory().messages())
                .extracting(ChatMessage::role)
                .doesNotContain("memory_profile", "memory_semantic");
        assertThat(context.promptMemory().messages())
                .extracting(ChatMessage::role)
                .doesNotContain("memory_profile", "memory_semantic");
        assertThat(semanticStore.listCandidates("user-1", 10))
                .extracting(MemoryItem::type)
                .contains("preference");

        MemoryItem candidate = semanticStore.listCandidates("user-1", 10).get(0);
        semanticStore.confirmCandidate(candidate.id(), "user-1");
        MemoryContext confirmed = memoryService.recallLongTerm(followUp, context, "concise answer preference");
        assertThat(confirmed.promptMemory().messages())
                .extracting(ChatMessage::role)
                .contains("memory_semantic");
    }

    @Test
    void isolatesShortTermMemoryByUserAndConversationId() {
        RagProperties properties = new RagProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new RagProperties.Memory("in-memory", true, 200000, 32000, 4000, 16, 86400L, "window", 4, true)
        );
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(properties);

        ChatRequest userOneRequest = userRequest("question from user one", "user-1");
        memoryService.recordTurn(userOneRequest, analysis(userOneRequest), response("answer for user one"));

        MemoryContext userOneContext = memoryService.load(userRequest("follow up", "user-1"));
        MemoryContext userTwoContext = memoryService.load(userRequest("follow up", "user-2"));

        assertThat(userOneContext.conversationId()).isEqualTo("session-1");
        assertThat(userOneContext.recentMessages())
                .extracting(ChatMessage::content)
                .contains("question from user one", "answer for user one");
        assertThat(userTwoContext.conversationId()).isEqualTo("session-1");
        assertThat(userTwoContext.recentMessages()).isEmpty();
    }

    @Test
    void compactsOnlyNewMessagesAfterTheSummaryCursor() {
        RagProperties properties = new RagProperties(
                null, null, null, null, null, null, null, null,
                memory(20, 1, 20, 16)
        );
        List<List<ChatMessage>> summarizedSegments = new ArrayList<>();
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(
                properties,
                (current, messages, maxTokens) -> {
                    summarizedSegments.add(List.copyOf(messages));
                    return new MemorySummary("summary-" + summarizedSegments.size(), true);
                },
                new BusinessConversationStateExtractor()
        );

        memoryService.recordTurn(request("question 1"), analysis(request("question 1")), response("answer 1"));
        memoryService.recordTurn(request("question 2"), analysis(request("question 2")), response("answer 2"));
        memoryService.recordTurn(request("question 3"), analysis(request("question 3")), response("answer 3"));

        assertThat(summarizedSegments).hasSize(2);
        assertThat(summarizedSegments.get(0)).extracting(ChatMessage::content)
                .containsExactly("question 1", "answer 1");
        assertThat(summarizedSegments.get(1)).extracting(ChatMessage::content)
                .containsExactly("question 2", "answer 2");
        MemoryContext context = memoryService.load(request("question 4"));
        assertThat(context.rawMessageCount()).isEqualTo(6);
        assertThat(context.recentMessages()).extracting(ChatMessage::content)
                .containsExactly("question 3", "answer 3");
    }

    @Test
    void preSummarizesAWholeOversizedTurnWithoutSplittingIt() {
        RagProperties properties = new RagProperties(
                null, null, null, null, null, null, null, null,
                memory(2048, 6, 64, 128)
        );
        List<List<ChatMessage>> summarizedSegments = new ArrayList<>();
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(
                properties,
                (current, messages, maxTokens) -> {
                    summarizedSegments.add(List.copyOf(messages));
                    return new MemorySummary("compressed", true);
                },
                new BusinessConversationStateExtractor()
        );

        ChatRequest request = request("long-question-" + "x".repeat(400));
        memoryService.recordTurn(request, analysis(request), response("long-answer-" + "y".repeat(400)));

        assertThat(summarizedSegments).hasSize(1);
        assertThat(summarizedSegments.get(0)).extracting(ChatMessage::role)
                .containsExactly("user", "assistant");
        MemoryContext context = memoryService.load(request("follow up"));
        assertThat(context.recentMessages()).extracting(ChatMessage::role)
                .containsExactly("user", "assistant");
        assertThat(context.rawMessageCount()).isEqualTo(2);
        assertThat(context.diagnostics().oversizedTurnCount()).isEqualTo(1);
    }

    @Test
    void doesNotCompactManyShortMessagesWhileTheyFitTheTokenBudget() {
        RagProperties properties = new RagProperties(
                null, null, null, null, null, null, null, null,
                memory(512, 6, 512, 128)
        );
        List<List<ChatMessage>> summarizedSegments = new ArrayList<>();
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(
                properties,
                (current, messages, maxTokens) -> {
                    summarizedSegments.add(List.copyOf(messages));
                    return new MemorySummary("compressed", true);
                },
                new BusinessConversationStateExtractor()
        );

        for (int index = 0; index < 10; index++) {
            ChatRequest request = request("q" + index);
            memoryService.recordTurn(request, analysis(request), response("a" + index));
        }

        assertThat(summarizedSegments).isEmpty();
        assertThat(memoryService.load(request("follow up")).recentMessages()).hasSize(20);
    }

    @Test
    void compactsOnlyThePrefixBeforeSixProtectedTurns() {
        RagProperties properties = new RagProperties(
                null, null, null, null, null, null, null, null,
                memory(90, 6, 90, 64)
        );
        List<List<ChatMessage>> summarizedSegments = new ArrayList<>();
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(
                properties,
                (current, messages, maxTokens) -> {
                    summarizedSegments.add(List.copyOf(messages));
                    return new MemorySummary("rolling-summary", true);
                },
                new BusinessConversationStateExtractor()
        );

        for (int index = 0; index < 7; index++) {
            ChatRequest request = request("question-" + index + "-" + "x".repeat(40));
            memoryService.recordTurn(request, analysis(request), response("answer-" + index + "-" + "y".repeat(40)));
        }

        assertThat(summarizedSegments).hasSize(1);
        assertThat(summarizedSegments.get(0)).extracting(ChatMessage::content)
                .containsExactly("question-0-" + "x".repeat(40), "answer-0-" + "y".repeat(40));
        MemoryContext context = memoryService.load(request("follow up"));
        assertThat(context.recentMessages()).hasSize(12);
        assertThat(context.recentMessages().get(0).content()).startsWith("question-1-");
        assertThat(context.diagnostics().protectedTurnCount()).isEqualTo(6);
    }

    @Test
    void keepsSixProtectedTurnsEvenWhenTheyExceedTheCompactionTrigger() {
        RagProperties properties = new RagProperties(
                null, null, null, null, null, null, null, null,
                memory(60, 6, 60, 32)
        );
        List<List<ChatMessage>> summarizedSegments = new ArrayList<>();
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(
                properties,
                (current, messages, maxTokens) -> {
                    summarizedSegments.add(List.copyOf(messages));
                    return new MemorySummary("summary", true);
                },
                new BusinessConversationStateExtractor()
        );

        for (int index = 0; index < 6; index++) {
            ChatRequest request = request("q" + index + "-" + "x".repeat(40));
            memoryService.recordTurn(request, analysis(request), response("a" + index + "-" + "y".repeat(40)));
        }

        assertThat(summarizedSegments).isEmpty();
        assertThat(memoryService.load(request("follow up")).recentMessages()).hasSize(12);
    }

    @Test
    void preSummarizesOnlyWhenATurnStrictlyExceedsItsThreshold() {
        String question = "question-" + "x".repeat(80);
        String answer = "answer-" + "y".repeat(80);
        int turnTokens = TokenEstimator.estimateMessages(List.of(
                new ChatMessage("user", question),
                new ChatMessage("assistant", answer)
        ));
        List<List<ChatMessage>> exactCalls = new ArrayList<>();
        InMemoryConversationMemoryService exactService = serviceWithSummaryCapture(
                memory(2048, 6, turnTokens, 64), exactCalls
        );
        ChatRequest exactRequest = request(question);
        exactService.recordTurn(exactRequest, analysis(exactRequest), response(answer));

        List<List<ChatMessage>> overCalls = new ArrayList<>();
        InMemoryConversationMemoryService overService = serviceWithSummaryCapture(
                memory(2048, 6, turnTokens - 1, 64), overCalls
        );
        ChatRequest overRequest = request(question);
        overService.recordTurn(overRequest, analysis(overRequest), response(answer));

        assertThat(exactCalls).isEmpty();
        assertThat(exactService.load(request("follow up")).recentMessages())
                .extracting(ChatMessage::role)
                .containsExactly("user", "assistant");
        assertThat(overCalls).hasSize(1);
        assertThat(overService.load(request("follow up")).recentMessages())
                .extracting(ChatMessage::role)
                .containsExactly("user", "assistant");
    }

    @Test
    void substitutesAPrecomputedTurnSummaryOnlyWhenTheActiveSourceBudgetIsExceeded() {
        RagProperties properties = new RagProperties(
                null, null, null, null, null, null, null, null,
                memory(2048, 6, 64, 128)
        );
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(
                properties,
                (current, messages, maxTokens) -> new MemorySummary("budget-safe summary", true),
                new BusinessConversationStateExtractor()
        );
        ChatRequest request = request("large-source-question-" + "x".repeat(12000));
        memoryService.recordTurn(request, analysis(request), response("large-source-answer-" + "y".repeat(12000)));

        MemoryContext context = memoryService.load(request("follow up"));

        assertThat(context.recentMessages()).extracting(ChatMessage::role)
                .containsExactly("conversation_turn_summary");
        assertThat(context.recentMessages().get(0).content())
                .contains("original messages [0, 2)", "budget-safe summary");
    }

    @Test
    void groupsOrphanAndUnexpectedMessagesAsAtomicTurns() {
        List<ConversationTurn> turns = ConversationTurn.group(List.of(
                new ChatMessage("user", "orphan user"),
                new ChatMessage("user", "paired user"),
                new ChatMessage("assistant", "paired answer"),
                new ChatMessage("tool", "orphan tool")
        ), 10);

        assertThat(turns).extracting(ConversationTurn::messageCount).containsExactly(1, 2, 1);
        assertThat(turns).extracting(ConversationTurn::startMessageIndex).containsExactly(10, 11, 13);
    }

    @Test
    void doesNotDuplicateACanonicalTurnWithABlankAssistantAnswer() {
        RagProperties properties = new RagProperties(
                null, null, null, null, null, null, null, null,
                memory(512, 6, 512, 64)
        );
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(properties);
        ChatRequest request = new ChatRequest(
                "same question",
                "kb-1",
                "session-1",
                List.of(
                        new ChatMessage("user", "same question"),
                        new ChatMessage("assistant", "")
                ),
                null
        );

        memoryService.recordTurn(request, analysis(request), response(""));

        assertThat(memoryService.load(request("follow up")).recentMessages())
                .extracting(ChatMessage::role)
                .containsExactly("user", "assistant");
    }

    @Test
    void postgresRestoresTurnSummariesWithoutChangingCanonicalHistory() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setName("turn-summary-persistence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false")
                .setType(EmbeddedDatabaseType.H2)
                .build();
        try {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(database);
            jdbcTemplate.execute("""
                    CREATE TABLE chat_conversations (
                        id VARCHAR(128) PRIMARY KEY,
                        user_id VARCHAR(128) NOT NULL
                    )
                    """);
            jdbcTemplate.execute("""
                    CREATE TABLE chat_messages (
                        conversation_id VARCHAR(128) NOT NULL,
                        seq INT NOT NULL,
                        role VARCHAR(32) NOT NULL,
                        content TEXT NOT NULL
                    )
                    """);
            String question = "large-question-" + "x".repeat(400);
            String answer = "large-answer-" + "y".repeat(400);
            jdbcTemplate.update("INSERT INTO chat_conversations (id, user_id) VALUES (?, ?)",
                    "session-1", "local-user");
            jdbcTemplate.update("INSERT INTO chat_messages (conversation_id, seq, role, content) VALUES (?, ?, ?, ?)",
                    "session-1", 0, "user", question);
            jdbcTemplate.update("INSERT INTO chat_messages (conversation_id, seq, role, content) VALUES (?, ?, ?, ?)",
                    "session-1", 1, "assistant", answer);

            RagProperties properties = new RagProperties(
                    null, null, null, null, null, null, null, null,
                    memory(2048, 6, 64, 128)
            );
            List<List<ChatMessage>> calls = new ArrayList<>();
            ConversationSummarizer summarizer = (current, messages, maxTokens) -> {
                calls.add(List.copyOf(messages));
                return new MemorySummary("persisted-turn-summary", true);
            };
            PostgresConversationMemoryService first = new PostgresConversationMemoryService(
                    jdbcTemplate,
                    properties,
                    summarizer,
                    new BusinessConversationStateExtractor(),
                    new InMemorySemanticMemoryStore(),
                    new InMemoryUserProfileStore(),
                    new BusinessLongTermMemoryExtractor()
            );
            ChatRequest request = request(question);
            first.recordTurn(request, analysis(request), response(answer));

            PostgresConversationMemoryService reloaded = new PostgresConversationMemoryService(
                    jdbcTemplate,
                    properties,
                    summarizer,
                    new BusinessConversationStateExtractor(),
                    new InMemorySemanticMemoryStore(),
                    new InMemoryUserProfileStore(),
                    new BusinessLongTermMemoryExtractor()
            );
            MemoryContext context = reloaded.load(request("follow up"));

            assertThat(calls).hasSize(1);
            assertThat(context.recentMessages()).extracting(ChatMessage::role)
                    .containsExactly("user", "assistant");
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_messages", Integer.class))
                    .isEqualTo(2);
            assertThat(jdbcTemplate.queryForList(
                    "SELECT content FROM chat_messages ORDER BY seq", String.class
            )).containsExactly(question, answer);
        } finally {
            database.shutdown();
        }
    }

    @Test
    void redisRestoresTurnSummariesWithoutCallingTheSummarizerAgain() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> listOperations = mock(ListOperations.class);
        Map<String, Map<Object, Object>> hashes = new LinkedHashMap<>();
        Map<String, List<String>> lists = new LinkedHashMap<>();
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(redisTemplate.hasKey(anyString())).thenAnswer(invocation ->
                hashes.containsKey(invocation.getArgument(0)));
        when(hashOperations.entries(anyString())).thenAnswer(invocation ->
                new LinkedHashMap<>(hashes.getOrDefault(invocation.getArgument(0), Map.of())));
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Map<?, ?> values = invocation.getArgument(1);
            hashes.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).putAll(values);
            return null;
        }).when(hashOperations).putAll(anyString(), anyMap());
        when(listOperations.range(anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenAnswer(invocation ->
                new ArrayList<>(lists.getOrDefault(invocation.getArgument(0), List.of())));
        when(listOperations.rightPush(anyString(), anyString())).thenAnswer(invocation -> {
            List<String> values = lists.computeIfAbsent(invocation.getArgument(0), ignored -> new ArrayList<>());
            values.add(invocation.getArgument(1));
            return (long) values.size();
        });
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            hashes.remove(key);
            lists.remove(key);
            return true;
        }).when(redisTemplate).delete(anyString());

        RagProperties properties = new RagProperties(
                null, null, null, null, null, null, null, null,
                memory(2048, 6, 64, 128)
        );
        List<List<ChatMessage>> calls = new ArrayList<>();
        ConversationSummarizer summarizer = (current, messages, maxTokens) -> {
            calls.add(List.copyOf(messages));
            return new MemorySummary("redis-turn-summary", true);
        };
        RedisConversationMemoryService first = new RedisConversationMemoryService(
                redisTemplate,
                properties,
                summarizer,
                new BusinessConversationStateExtractor(),
                new InMemorySemanticMemoryStore(),
                new InMemoryUserProfileStore(),
                new BusinessLongTermMemoryExtractor()
        );
        ChatRequest request = request("large-question-" + "x".repeat(400));
        first.recordTurn(request, analysis(request), response("large-answer-" + "y".repeat(400)));

        RedisConversationMemoryService reloaded = new RedisConversationMemoryService(
                redisTemplate,
                properties,
                summarizer,
                new BusinessConversationStateExtractor(),
                new InMemorySemanticMemoryStore(),
                new InMemoryUserProfileStore(),
                new BusinessLongTermMemoryExtractor()
        );
        MemoryContext context = reloaded.load(request("follow up"));

        assertThat(calls).hasSize(1);
        assertThat(context.recentMessages()).extracting(ChatMessage::role)
                .containsExactly("user", "assistant");
    }

    @Test
    void recallsOriginalMessagesFromTheMatchingCompactedTurnRange() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setName("historical-raw-recall;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false")
                .setType(EmbeddedDatabaseType.H2)
                .build();
        try {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(database);
            jdbcTemplate.execute("""
                    CREATE TABLE chat_conversations (
                        id VARCHAR(128) PRIMARY KEY,
                        user_id VARCHAR(128) NOT NULL
                    )
                    """);
            jdbcTemplate.execute("""
                    CREATE TABLE chat_messages (
                        conversation_id VARCHAR(128) NOT NULL,
                        seq INT NOT NULL,
                        role VARCHAR(32) NOT NULL,
                        content TEXT NOT NULL
                    )
                    """);
            jdbcTemplate.update("INSERT INTO chat_conversations (id, user_id) VALUES (?, ?)",
                    "session-1", "local-user");
            for (int index = 0; index < 7; index++) {
                String question = index == 0
                        ? "refund reference ZX-2048 " + "x".repeat(400)
                        : "other question " + index;
                String answer = index == 0 ? "refund source detail " + "y".repeat(400) : "other answer " + index;
                jdbcTemplate.update("INSERT INTO chat_messages (conversation_id, seq, role, content) VALUES (?, ?, ?, ?)",
                        "session-1", index * 2, "user", question);
                jdbcTemplate.update("INSERT INTO chat_messages (conversation_id, seq, role, content) VALUES (?, ?, ?, ?)",
                        "session-1", index * 2 + 1, "assistant", answer);
            }
            RagProperties properties = new RagProperties(
                    null, null, null, null, null, null, null, null,
                    memory(100, 6, 64, 64)
            );
            PostgresConversationMemoryService memoryService = new PostgresConversationMemoryService(
                    jdbcTemplate,
                    properties,
                    (current, messages, maxTokens) -> new MemorySummary("refund reference ZX-2048", true),
                    new BusinessConversationStateExtractor(),
                    new InMemorySemanticMemoryStore(),
                    new InMemoryUserProfileStore(),
                    new BusinessLongTermMemoryExtractor()
            );
            ChatRequest latest = request("other question 6");
            memoryService.recordTurn(latest, analysis(latest), response("other answer 6"));

            MemoryContext context = memoryService.load(request("Where is refund reference ZX-2048?"));

            assertThat(context.rawRecallMessages()).hasSize(1);
            assertThat(context.rawRecallMessages().get(0).content())
                    .contains("source messages [0, 2)", "refund source detail");
            assertThat(context.recentMessages()).extracting(ChatMessage::content)
                    .doesNotContain("refund source detail " + "y".repeat(400));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void measuresCriticalSummaryFactCoverage() {
        List<String> facts = List.of("ABC123456", "2026-08-01", "200000 tokens");

        assertThat(SummaryFactCoverage.coverage(
                "Order ABC123456 is due on 2026-08-01.", facts
        )).isBetween(0.6, 0.7);
    }

    @Test
    void localSummaryFallbackPreservesCriticalFactsUnderTokenPressure() {
        WindowConversationSummarizer summarizer = new WindowConversationSummarizer();
        List<ChatMessage> messages = List.of(
                new ChatMessage("user", "background ".repeat(100)
                        + "Order ABC123456 must finish on 2026-08-01 with a 200000 token context."),
                new ChatMessage("assistant", "Decision confirmed.")
        );

        MemorySummary summary = summarizer.summarize("", messages, 80);

        assertThat(TokenEstimator.estimate(summary.content())).isLessThanOrEqualTo(80);
        assertThat(summary.content()).contains("ABC123456", "2026-08-01", "200000 token");
    }

    @Test
    void localSummaryFallbackPreservesGoalsPreferencesAndDecisionsWithoutNumbers() {
        WindowConversationSummarizer summarizer = new WindowConversationSummarizer();
        List<ChatMessage> messages = List.of(new ChatMessage(
                "user",
                "background ".repeat(100)
                        + "Decision: use PostgreSQL. User prefers concise Chinese answers."
        ));

        MemorySummary summary = summarizer.summarize("", messages, 64);

        assertThat(summary.content())
                .containsIgnoringCase("Decision: use PostgreSQL")
                .containsIgnoringCase("prefers concise Chinese answers");
    }

    @Test
    void llmSummaryFallsBackWhenAnyCriticalFactIsLost() {
        StubLlmGateway gateway = new StubLlmGateway("The user discussed an order and a deadline.");
        LlmConversationSummarizer summarizer = new LlmConversationSummarizer(
                gateway,
                new WindowConversationSummarizer()
        );
        List<ChatMessage> messages = List.of(new ChatMessage(
                "user",
                "Order ABC123456 must finish on 2026-08-01 within 200000 tokens."
        ));

        MemorySummary summary = summarizer.summarize("", messages, 128);

        assertThat(summary.content()).contains("ABC123456", "2026-08-01", "200000 tokens");
        assertThat(summary.fallbackUsed()).isTrue();
        assertThat(gateway.lastPrompt)
                .contains("Preserve every exact identifier")
                .contains("ABC123456");
    }

    @Test
    void acceptsLlmSummaryWhenCriticalFactCoverageIsComplete() {
        StubLlmGateway gateway = new StubLlmGateway(
                "Order ABC123456 is due on 2026-08-01 within 200000 tokens."
        );
        LlmConversationSummarizer summarizer = new LlmConversationSummarizer(
                gateway,
                new WindowConversationSummarizer()
        );

        MemorySummary summary = summarizer.summarize("", List.of(new ChatMessage(
                "user",
                "Order ABC123456 is due on 2026-08-01 within 200000 tokens."
        )), 128);

        assertThat(summary.content()).isEqualTo(gateway.answer);
        assertThat(summary.factCoverage()).isEqualTo(1.0);
        assertThat(summary.fallbackUsed()).isFalse();
    }

    @Test
    void requestsStructuredSectionsForAnOversizedTurnSummary() {
        StubLlmGateway gateway = new StubLlmGateway(
                "User goal: order ABC123456. Assistant result: due 2026-08-01."
        );
        LlmConversationSummarizer summarizer = new LlmConversationSummarizer(
                gateway,
                new WindowConversationSummarizer()
        );

        summarizer.summarizeTurn(List.of(new ChatMessage(
                "user",
                "Order ABC123456 is due on 2026-08-01."
        )), 128);

        assertThat(gateway.lastPrompt)
                .contains("User goal")
                .contains("Assistant result")
                .contains("Decisions and constraints")
                .contains("Unresolved items");
    }

    @Test
    void llmSummaryPromptStaysInsideItsConfiguredContextWindow() {
        StubLlmGateway gateway = new StubLlmGateway("No exact facts supplied.");
        LlmConversationSummarizer summarizer = new LlmConversationSummarizer(
                gateway,
                new WindowConversationSummarizer(),
                4096
        );
        List<ChatMessage> messages = List.of(
                new ChatMessage("user", "background ".repeat(5000)),
                new ChatMessage("assistant", "response ".repeat(5000))
        );

        summarizer.summarize("", messages, 128);

        assertThat(TokenEstimator.estimate(gateway.lastPrompt)).isLessThanOrEqualTo(4096);
    }

    @Test
    void estimatesCjkAndLatinTokensWithoutUsingCharacterCount() {
        assertThat(TokenEstimator.estimate("上下文压缩")).isEqualTo(5);
        assertThat(TokenEstimator.estimate("abcdefghijklmnop")).isEqualTo(4);
    }

    @Test
    void truncatesTextToTheRequestedTokenBudget() {
        String truncated = TokenEstimator.truncate("上下文压缩策略".repeat(20), 12);

        assertThat(truncated).endsWith("...");
        assertThat(TokenEstimator.estimate(truncated)).isLessThanOrEqualTo(12);
    }

    @Test
    void ignoresAnAlreadyRecordedTurnAndCanForgetItsWorkingMemory() {
        RagProperties properties = new RagProperties(
                null, null, null, null, null, null, null, null,
                new RagProperties.Memory("in-memory", true, 200000, 32000, 4000, 16, 86400L)
        );
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(properties);
        ChatRequest request = userRequest("same question", "user-1");
        ChatResponse response = response("same answer");

        memoryService.recordTurn(request, analysis(request), response);
        memoryService.recordTurn(request, analysis(request), response);
        assertThat(memoryService.load(userRequest("follow up", "user-1")).recentMessages()).hasSize(2);

        memoryService.forget("user-1", "session-1");
        assertThat(memoryService.load(userRequest("follow up", "user-1")).recentMessages()).isEmpty();
    }

    private ChatRequest request(String query) {
        return new ChatRequest(query, "kb-1", "session-1", List.of(), null);
    }

    private InMemoryConversationMemoryService serviceWithSummaryCapture(
            RagProperties.Memory memory,
            List<List<ChatMessage>> calls
    ) {
        RagProperties properties = new RagProperties(
                null, null, null, null, null, null, null, null, memory
        );
        return new InMemoryConversationMemoryService(
                properties,
                (current, messages, maxTokens) -> {
                    calls.add(List.copyOf(messages));
                    return new MemorySummary("turn-summary", true);
                },
                new BusinessConversationStateExtractor()
        );
    }

    private RagProperties.Memory memory(
            int compactionTriggerTokens,
            int protectedRecentTurns,
            int oversizedTurnTokens,
            int turnSummaryMaxTokens
    ) {
        return new RagProperties.Memory(
                "in-memory",
                true,
                4096,
                null,
                128,
                16,
                86400L,
                "window",
                4,
                true,
                null,
                compactionTriggerTokens,
                protectedRecentTurns,
                oversizedTurnTokens,
                turnSummaryMaxTokens
        );
    }

    private static final class StubLlmGateway implements LlmGateway {
        private final String answer;
        private String lastPrompt = "";

        private StubLlmGateway(String answer) {
            this.answer = answer;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public String complete(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
            lastPrompt = userPrompt;
            return answer;
        }
    }

    private ChatRequest userRequest(String query, String userId) {
        return new ChatRequest(
                query,
                "kb-1",
                "session-1",
                List.of(),
                new ChatOptions(null, null, null, null, null, false, userId)
        );
    }

    private QueryAnalysisResponse analysis(ChatRequest request) {
        return new QueryAnalysisResponse(
                request.conversationId(),
                request.knowledgeBaseId(),
                request.query(),
                request.query(),
                request.query(),
                "knowledge",
                0.80,
                "knowledge_retrieval",
                false,
                false,
                request.normalizedHistory().size(),
                List.of(request.query()),
                List.of("test")
        );
    }

    private ChatResponse response(String answer) {
        return new ChatResponse(
                "session-1",
                answer,
                "knowledge",
                0.80,
                "knowledge_retrieval",
                "question",
                "question",
                List.of("question"),
                List.of(),
                List.of(),
                true,
                "llm_generated",
                "rag_retrieval",
                List.of(),
                List.of()
        );
    }
}
