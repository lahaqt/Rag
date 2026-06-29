package com.example.ragagent.controller;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.service.ChatOrchestrator;
import com.example.ragagent.service.MultiAgentOrchestrator;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
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
    private final MultiAgentOrchestrator multiAgentOrchestrator;
    private final ExecutorService chatExecutor;

    public ChatController(
            ChatOrchestrator chatOrchestrator,
            MultiAgentOrchestrator multiAgentOrchestrator,
            ExecutorService chatExecutor
    ) {
        this.chatOrchestrator = chatOrchestrator;
        this.multiAgentOrchestrator = multiAgentOrchestrator;
        this.chatExecutor = chatExecutor;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatOrchestrator.answer(request);
    }

    @PostMapping("/multi-agent")
    public ChatResponse multiAgentChat(@Valid @RequestBody ChatRequest request) {
        return multiAgentOrchestrator.answer(request);
    }

    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        chatExecutor.execute(() -> {
            try {
                sendResponse(emitter, chatOrchestrator.answer(request));
            } catch (Exception exception) {
                completeWithError(emitter, exception);
            }
        });
        return emitter;
    }

    @PostMapping(path = "/multi-agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter multiAgentStream(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        chatExecutor.execute(() -> {
            try {
                sendResponse(emitter, multiAgentOrchestrator.answer(request));
            } catch (Exception exception) {
                completeWithError(emitter, exception);
            }
        });
        return emitter;
    }

    private void sendResponse(SseEmitter emitter, ChatResponse response) throws IOException {
        emitter.send(SseEmitter.event()
                .name("metadata")
                .data(Map.of(
                        "intent", response.intent(),
                        "route", response.route(),
                        "rewrittenQuery", response.rewrittenQuery(),
                        "toolName", response.toolName(),
                        "finishReason", response.finishReason(),
                        "traceId", response.traceId(),
                        "spanId", response.spanId(),
                        "agentTrace", response.agentTrace()
                )));
        for (String chunk : chunks(response.answer(), 64)) {
            emitter.send(SseEmitter.event().name("delta").data(Map.of("content", chunk)));
        }
        emitter.send(SseEmitter.event().name("citations").data(response.citations()));
        emitter.send(SseEmitter.event().name("done").data(response));
        emitter.complete();
    }

    private void completeWithError(SseEmitter emitter, Exception exception) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("message", exception.getMessage())));
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
}
