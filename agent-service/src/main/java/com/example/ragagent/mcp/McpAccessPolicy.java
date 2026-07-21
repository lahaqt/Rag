package com.example.ragagent.mcp;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Separates trusted, file-configured MCP integrations from runtime administration.
 * Runtime registrations are disabled unless a deployment explicitly opts in and
 * restricts them to listed remote HTTP hosts. Runtime stdio is never permitted.
 */
@Component
public class McpAccessPolicy {
    private final boolean runtimeRegistrationEnabled;
    private final boolean directToolCallEnabled;
    private final Set<String> allowedRuntimeHttpHosts;

    public McpAccessPolicy(
            @Value("${rag.mcp.runtime-registration-enabled:false}") boolean runtimeRegistrationEnabled,
            @Value("${rag.mcp.direct-tool-call-enabled:false}") boolean directToolCallEnabled,
            @Value("${rag.mcp.runtime-allowed-http-hosts:}") String allowedRuntimeHttpHosts
    ) {
        this.runtimeRegistrationEnabled = runtimeRegistrationEnabled;
        this.directToolCallEnabled = directToolCallEnabled;
        this.allowedRuntimeHttpHosts = Arrays.stream(allowedRuntimeHttpHosts.split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    void requireRuntimeRegistrationAllowed(McpServerDefinition definition) {
        if (!runtimeRegistrationEnabled) {
            throw forbidden("Runtime MCP registration is disabled");
        }
        if (definition.isStdio()) {
            throw forbidden("Runtime MCP stdio servers are not allowed");
        }
        String host = definition.endpoint() == null || definition.endpoint().getHost() == null
                ? ""
                : definition.endpoint().getHost().toLowerCase(Locale.ROOT);
        if (!allowedRuntimeHttpHosts.contains(host)) {
            throw forbidden("MCP endpoint host is not allowed for runtime registration");
        }
    }

    public void requireDirectToolCallsAllowed() {
        if (!directToolCallEnabled) {
            throw forbidden("Direct MCP tool calls are disabled; use the agent execution path");
        }
    }

    private ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
