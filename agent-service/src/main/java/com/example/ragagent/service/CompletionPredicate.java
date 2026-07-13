package com.example.ragagent.service;

/** A small, locally evaluable completion contract; never an arbitrary LLM expression. */
record CompletionPredicate(Kind kind, int minimumEvidence) {
    enum Kind { TOOL_SUCCESS, RETRIEVAL_EVIDENCE, WEB_EVIDENCE, STRUCTURED_FIELD }
    CompletionPredicate { kind = kind == null ? Kind.TOOL_SUCCESS : kind; minimumEvidence = Math.max(0, minimumEvidence); }
    boolean matches(AgentToolResult result) {
        if (result == null || !result.success()) return false;
        return switch (kind) {
            case TOOL_SUCCESS, STRUCTURED_FIELD -> true;
            case RETRIEVAL_EVIDENCE -> result.retrievalHits().size() >= minimumEvidence;
            case WEB_EVIDENCE -> result.webSearchResults().size() >= minimumEvidence;
        };
    }
}
