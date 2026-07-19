package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatMessage;
import java.util.List;
import java.util.Map;

public record MemoryContext(
        String conversationId,
        List<ChatMessage> recentMessages,
        String rollingSummary,
        Map<String, String> dialogState,
        List<MemoryItem> semanticMemories,
        UserProfile userProfile,
        int rawMessageCount,
        int summaryVersion,
        MemoryDiagnostics diagnostics
) {
    public MemoryContext(
            String conversationId,
            List<ChatMessage> recentMessages,
            String rollingSummary,
            Map<String, String> dialogState,
            List<MemoryItem> semanticMemories,
            UserProfile userProfile,
            int rawMessageCount,
            int summaryVersion
    ) {
        this(
                conversationId,
                recentMessages,
                rollingSummary,
                dialogState,
                semanticMemories,
                userProfile,
                rawMessageCount,
                summaryVersion,
                MemoryDiagnostics.empty()
        );
    }

    public MemoryContext(
            String conversationId,
            List<ChatMessage> recentMessages,
            String rollingSummary,
            Map<String, String> dialogState,
            int rawMessageCount,
            int summaryVersion
    ) {
        this(
                conversationId,
                recentMessages,
                rollingSummary,
                dialogState,
                List.of(),
                new UserProfile("", Map.of(), null),
                rawMessageCount,
                summaryVersion,
                MemoryDiagnostics.empty()
        );
    }

    public MemoryContext {
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        rollingSummary = rollingSummary == null ? "" : rollingSummary;
        dialogState = dialogState == null ? Map.of() : Map.copyOf(dialogState);
        semanticMemories = semanticMemories == null ? List.of() : List.copyOf(semanticMemories);
        userProfile = userProfile == null ? new UserProfile("", Map.of(), null) : userProfile;
        diagnostics = diagnostics == null ? MemoryDiagnostics.empty() : diagnostics;
    }

    public MemoryAnalysisContext analysisMemory() {
        return new MemoryAnalysisContext(recentMessages);
    }

    public MemoryPromptContext promptMemory() {
        return new MemoryPromptContext(recentMessages, rollingSummary, dialogState, semanticMemories, userProfile);
    }

    public MemoryStateContext stateMemory() {
        return new MemoryStateContext(dialogState);
    }

    public List<ChatMessage> analysisHistory() {
        return analysisMemory().messages();
    }

    public List<ChatMessage> promptHistory() {
        return promptMemory().messages();
    }

    public MemoryContext withSemanticMemories(List<MemoryItem> recalledMemories) {
        return new MemoryContext(
                conversationId,
                recentMessages,
                rollingSummary,
                dialogState,
                recalledMemories,
                userProfile,
                rawMessageCount,
                summaryVersion,
                diagnostics
        );
    }
}
