package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;

/** Payload persisted with a canonical chat turn for asynchronous memory projection. */
public record MemoryProjectionEvent(ChatRequest request, ChatResponse response) {
}
