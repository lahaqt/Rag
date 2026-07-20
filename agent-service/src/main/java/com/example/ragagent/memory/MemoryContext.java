package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatMessage;
import java.util.List;
import java.util.Map;

public record MemoryContext(
        String conversationId,
        List<ChatMessage> recentMessages,
        List<ChatMessage> rawRecallMessages,
        String rollingSummary,
        Map<String, String> dialogState,
        List<MemoryItem> semanticMemories,
        UserProfile userProfile,
        int rawMessageCount,
        int summaryVersion,
        MemoryDiagnostics diagnostics,
        MemoryRecallDiagnostics recallDiagnostics
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
                List.of(),
                rollingSummary,
                dialogState,
                semanticMemories,
                userProfile,
                rawMessageCount,
                summaryVersion,
                MemoryDiagnostics.empty(),
                MemoryRecallDiagnostics.empty()
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
                List.of(),
                rollingSummary,
                dialogState,
                List.of(),
                new UserProfile("", Map.of(), null),
                rawMessageCount,
                summaryVersion,
                MemoryDiagnostics.empty(),
                MemoryRecallDiagnostics.empty()
        );
    }

    public MemoryContext {
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        rawRecallMessages = rawRecallMessages == null ? List.of() : List.copyOf(rawRecallMessages);
        rollingSummary = rollingSummary == null ? "" : rollingSummary;
        dialogState = dialogState == null ? Map.of() : Map.copyOf(dialogState);
        semanticMemories = semanticMemories == null ? List.of() : List.copyOf(semanticMemories);
        userProfile = userProfile == null ? new UserProfile("", Map.of(), null) : userProfile;
        diagnostics = diagnostics == null ? MemoryDiagnostics.empty() : diagnostics;
        recallDiagnostics = recallDiagnostics == null ? MemoryRecallDiagnostics.empty() : recallDiagnostics;
    }

    public MemoryAnalysisContext analysisMemory() {
        return new MemoryAnalysisContext(recentMessages);
    }

    public MemoryPromptContext promptMemory() {
        return new MemoryPromptContext(
                recentMessages,
                rawRecallMessages,
                rollingSummary,
                dialogState,
                semanticMemories,
                userProfile
        );
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

    public MemoryContext withLongTermMemory(
            List<MemoryItem> recalledMemories,
            UserProfile selectedProfile,
            MemoryRecallDiagnostics recall
    ) {
        return new MemoryContext(
                conversationId,
                recentMessages,
                rawRecallMessages,
                rollingSummary,
                dialogState,
                recalledMemories,
                selectedProfile,
                rawMessageCount,
                summaryVersion,
                diagnostics,
                recall
        );
    }

    public MemoryContext withSemanticMemories(List<MemoryItem> recalledMemories) {
        return withLongTermMemory(recalledMemories, userProfile, recallDiagnostics);
    }
}
