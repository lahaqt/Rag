package com.example.ragagent.controller;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.observability.TraceContextProvider;
import com.example.ragagent.observability.TraceContextSnapshot;
import com.example.ragagent.service.ChatStreamSink;
import com.example.ragagent.service.ChatOrchestrator;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import io.micrometer.tracing.Span;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatOrchestrator chatOrchestrator;
    private final ExecutorService chatExecutor;
    private final TraceContextProvider traceContextProvider;

    public ChatController(
            ChatOrchestrator chatOrchestrator,
            ExecutorService chatExecutor
    ) {
        this(chatOrchestrator, chatExecutor, null);
    }

    @Autowired
    public ChatController(
            ChatOrchestrator chatOrchestrator,
            ExecutorService chatExecutor,
            TraceContextProvider traceContextProvider
    ) {
        this.chatOrchestrator = chatOrchestrator;
        this.chatExecutor = chatExecutor;
        this.traceContextProvider = traceContextProvider;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatOrchestrator.answer(request);
    }

    @PostMapping("/multi-agent")
    public ChatResponse multiAgentChat(@Valid @RequestBody ChatRequest request) {
        return chatOrchestrator.answerMultiAgent(request);
    }

    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        TraceContextSnapshot traceContext = currentTraceContext();
        Span parentSpan = currentSpan();
        chatExecutor.execute(() -> {
            try {
                SseChatStreamSink streamSink = new SseChatStreamSink(emitter);
                ChatResponse response = answer(request, streamSink, traceContext, parentSpan);
                sendResponse(emitter, response, streamSink);
            } catch (Exception exception) {
                completeWithError(emitter, exception);
            }
        });
        return emitter;
    }

    @PostMapping(path = "/multi-agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter multiAgentStream(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        TraceContextSnapshot traceContext = currentTraceContext();
        Span parentSpan = currentSpan();
        chatExecutor.execute(() -> {
            try {
                SseChatStreamSink streamSink = new SseChatStreamSink(emitter);
                ChatResponse response = answerMultiAgent(request, streamSink, traceContext, parentSpan);
                sendResponse(emitter, response, streamSink);
            } catch (Exception exception) {
                completeWithError(emitter, exception);
            }
        });
        return emitter;
    }

    private ChatResponse answer(ChatRequest request, ChatStreamSink streamSink, TraceContextSnapshot traceContext) {
        return answer(request, streamSink, traceContext, null);
    }

    private ChatResponse answer(
            ChatRequest request,
            ChatStreamSink streamSink,
            TraceContextSnapshot traceContext,
            Span parentSpan
    ) {
        return traceContext != null && traceContext.available()
                ? chatOrchestrator.answer(request, streamSink, traceContext, parentSpan)
                : chatOrchestrator.answer(request, streamSink);
    }

    private ChatResponse answerMultiAgent(ChatRequest request, ChatStreamSink streamSink, TraceContextSnapshot traceContext) {
        return answerMultiAgent(request, streamSink, traceContext, null);
    }

    private ChatResponse answerMultiAgent(
            ChatRequest request,
            ChatStreamSink streamSink,
            TraceContextSnapshot traceContext,
            Span parentSpan
    ) {
        return traceContext != null && traceContext.available()
                ? chatOrchestrator.answerMultiAgent(request, streamSink, traceContext, parentSpan)
                : chatOrchestrator.answerMultiAgent(request, streamSink);
    }

    private TraceContextSnapshot currentTraceContext() {
        if (traceContextProvider == null) {
            return TraceContextSnapshot.empty();
        }
        return traceContextProvider.current();
    }

    private Span currentSpan() {
        return traceContextProvider == null ? null : traceContextProvider.currentSpan();
    }

    private void sendResponse(SseEmitter emitter, ChatResponse response, SseChatStreamSink streamSink) throws IOException {
        emitter.send(SseEmitter.event()
                .name("metadata")
                .data(metadata(response)));
        if (!streamSink.answerEmitted()) {
            for (String chunk : chunks(response.answer(), 64)) {
                emitter.send(SseEmitter.event().name("answer_delta").data(Map.of("content", chunk)));
            }
        }
        emitter.send(SseEmitter.event().name("citations").data(response.citations()));
        emitter.send(SseEmitter.event().name("done").data(response));
        emitter.complete();
    }

    private void completeWithError(SseEmitter emitter, Exception exception) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("message", exception.getMessage() == null ? "stream failed" : exception.getMessage())));
        } catch (IOException ignored) {
            // The client may already have disconnected.
        }
        emitter.completeWithError(exception);
    }

    private Iterable<String> chunks(String value, int chunkSize) {
        return () -> new java.util.Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return value != null && index < value.length();
            }

            @Override
            public String next() {
                int end = Math.min(value.length(), index + chunkSize);
                String chunk = value.substring(index, end);
                index = end;
                return chunk;
            }
        };
    }

    private Map<String, Object> metadata(ChatResponse response) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", response.conversationId());
        data.put("intent", response.intent());
        data.put("route", response.route());
        data.put("rewrittenQuery", response.rewrittenQuery());
        data.put("toolName", response.toolName());
        data.put("finishReason", response.finishReason());
        data.put("traceId", response.traceId());
        data.put("spanId", response.spanId());
        data.put("agentTrace", response.agentTrace());
        return data;
    }

    private static class SseChatStreamSink implements ChatStreamSink {
        private final SseEmitter emitter;
        private boolean answerEmitted;

        private SseChatStreamSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public synchronized void trace(com.example.ragagent.dto.AgentTraceStep step) {
            try {
                emitter.send(SseEmitter.event().name("trace_delta").data(step));
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to send trace stream event.", exception);
            }
        }

        @Override
        public synchronized void answerDelta(String content) {
            if (content == null || content.isEmpty()) {
                return;
            }
            answerEmitted = true;
            try {
                emitter.send(SseEmitter.event().name("answer_delta").data(Map.of("content", content)));
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to send answer stream event.", exception);
            }
        }

        @Override
        public synchronized void answerReset(String reason) {
            answerEmitted = false;
            try {
                emitter.send(SseEmitter.event().name("answer_reset").data(Map.of("reason", reason)));
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to send answer reset stream event.", exception);
            }
        }

        private boolean answerEmitted() {
            return answerEmitted;
        }
    }
}
