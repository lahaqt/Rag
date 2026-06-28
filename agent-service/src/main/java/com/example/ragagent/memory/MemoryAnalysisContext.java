package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatMessage;
import java.util.List;

public record MemoryAnalysisContext(List<ChatMessage> messages) {
    public MemoryAnalysisContext {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
