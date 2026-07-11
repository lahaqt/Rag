package com.example.ragagent.service;

import com.example.ragagent.a2a.A2aTask;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.observability.TraceContextSnapshot;
import io.micrometer.tracing.Span;
import org.springframework.stereotype.Service;

/** Thin HTTP/A2A facade over the unified Spring AI Alibaba runtime. */
@Service
public class ChatOrchestrator {
    private final SpringAiAlibabaAgentRuntime runtime;

    public ChatOrchestrator(SpringAiAlibabaAgentRuntime runtime) {
        this.runtime = runtime;
    }

    public ChatResponse answer(ChatRequest request) {
        return runtime.answer(request);
    }

    public ChatResponse answer(ChatRequest request, ChatStreamSink streamSink) {
        return runtime.answer(request, streamSink, TraceContextSnapshot.empty());
    }

    public ChatResponse answer(
            ChatRequest request,
            ChatStreamSink streamSink,
            TraceContextSnapshot fallbackTraceContext
    ) {
        return runtime.answer(request, streamSink, fallbackTraceContext);
    }

    public ChatResponse answer(
            ChatRequest request,
            ChatStreamSink streamSink,
            TraceContextSnapshot fallbackTraceContext,
            Span parentSpan
    ) {
        return runtime.answer(request, streamSink, fallbackTraceContext, parentSpan);
    }

    public ChatResponse answerMultiAgent(ChatRequest request) {
        return runtime.answerMultiAgent(request);
    }

    public ChatResponse answerMultiAgent(ChatRequest request, ChatStreamSink streamSink) {
        return runtime.answerMultiAgent(request, streamSink, TraceContextSnapshot.empty());
    }

    public ChatResponse answerMultiAgent(
            ChatRequest request,
            ChatStreamSink streamSink,
            TraceContextSnapshot fallbackTraceContext
    ) {
        return runtime.answerMultiAgent(request, streamSink, fallbackTraceContext);
    }

    public ChatResponse answerMultiAgent(
            ChatRequest request,
            ChatStreamSink streamSink,
            TraceContextSnapshot fallbackTraceContext,
            Span parentSpan
    ) {
        return runtime.answerMultiAgent(request, streamSink, fallbackTraceContext, parentSpan);
    }

    public A2aTask answerTask(ChatRequest request) {
        return runtime.answerTask(request);
    }
}
