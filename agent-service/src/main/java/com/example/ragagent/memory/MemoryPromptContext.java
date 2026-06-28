package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record MemoryPromptContext(
        List<ChatMessage> recentMessages,
        String rollingSummary,
        Map<String, String> dialogState,
        List<MemoryItem> semanticMemories,
        UserProfile userProfile
) {
    public MemoryPromptContext(
            List<ChatMessage> recentMessages,
            String rollingSummary,
            Map<String, String> dialogState
    ) {
        this(recentMessages, rollingSummary, dialogState, List.of(), new UserProfile("", Map.of(), null));
    }

    public MemoryPromptContext {
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        rollingSummary = rollingSummary == null ? "" : rollingSummary;
        dialogState = dialogState == null ? Map.of() : Map.copyOf(dialogState);
        semanticMemories = semanticMemories == null ? List.of() : List.copyOf(semanticMemories);
        userProfile = userProfile == null ? new UserProfile("", Map.of(), null) : userProfile;
    }

    public List<ChatMessage> messages() {
        List<ChatMessage> history = new ArrayList<>();
        if (!rollingSummary.isBlank()) {
            history.add(new ChatMessage(
                    "memory_summary",
                    "Conversation summary: " + rollingSummary
            ));
        }
        if (!dialogState.isEmpty()) {
            history.add(new ChatMessage(
                    "memory_state",
                    "Conversation state: " + stateText()
            ));
        }
        if (userProfile != null && !userProfile.isEmpty()) {
            history.add(new ChatMessage(
                    "memory_profile",
                    "User profile: " + mapText(userProfile.facts())
            ));
        }
        if (!semanticMemories.isEmpty()) {
            history.add(new ChatMessage(
                    "memory_semantic",
                    "Relevant long-term memories: " + semanticMemoryText()
            ));
        }
        history.addAll(recentMessages);
        return List.copyOf(history);
    }

    private String stateText() {
        return mapText(dialogState);
    }

    private String mapText(Map<String, String> values) {
        StringBuilder builder = new StringBuilder();
        values.forEach((key, value) -> {
            if (!builder.isEmpty()) {
                builder.append("; ");
            }
            builder.append(key).append("=").append(value);
        });
        return builder.toString();
    }

    private String semanticMemoryText() {
        StringBuilder builder = new StringBuilder();
        for (MemoryItem item : semanticMemories) {
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(item.type()).append(": ").append(item.content());
        }
        return builder.toString();
    }
}
