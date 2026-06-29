package com.example.ragagent.history;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConversationTitleGeneratorTests {
    private final ConversationTitleGenerator generator = new ConversationTitleGenerator();

    @Test
    void returnsDefaultTitleForBlankInput() {
        assertThat(generator.generate("   ")).isEqualTo("New conversation");
    }

    @Test
    void removesSlashCommandAndLeadingFillerWords() {
        assertThat(generator.generate("/plan 请帮我优化自动生成对话标题功能，并给出实现方案"))
                .isEqualTo("优化自动生成对话标题功能");
    }

    @Test
    void keepsConciseQuestionTopicWithoutTrailingPunctuation() {
        assertThat(generator.generate("请问 RAG 系统里的 query-analysis-service 负责什么？"))
                .isEqualTo("RAG 系统里的 query-analysis-service 负责什么");
    }

    @Test
    void truncatesLongTitleAfterCleaning() {
        assertThat(generator.generate("帮我分析当前普通聊天链路里从 query-analysis-service 到 agent-service 再到 knowledge-service 的完整调用关系"))
                .isEqualTo("分析当前普通聊天链路里从 query-analysis-service 到...");
    }
}
