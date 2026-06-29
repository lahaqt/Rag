package com.example.ragagent.service;

import com.example.ragagent.dto.AgentTraceStep;

public interface ChatStreamSink {
    ChatStreamSink NOOP = new ChatStreamSink() {
    };

    static ChatStreamSink noop() {
        return NOOP;
    }

    default void trace(AgentTraceStep step) {
    }

    default void answerDelta(String content) {
    }

    default void answerReset(String reason) {
    }
}
