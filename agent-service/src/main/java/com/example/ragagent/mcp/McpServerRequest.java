package com.example.ragagent.mcp;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

public record McpServerRequest(
        String id,
        @NotBlank String name,
        String transport,
        String endpoint,
        String command,
        List<String> args,
        Map<String, String> environment,
        String workingDirectory,
        String bearerToken,
        Boolean enabled
) {
    public McpServerRequest {
        args = args == null ? List.of() : List.copyOf(args);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }
}
