package com.example.ragagent.mcp;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public record McpServerDefinition(
        String id,
        String name,
        String transport,
        URI endpoint,
        String command,
        List<String> args,
        Map<String, String> environment,
        String workingDirectory,
        String bearerToken,
        boolean enabled
) {
    private static final Pattern SAFE_ID = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
    public static final String TRANSPORT_HTTP = "streamable_http";
    public static final String TRANSPORT_STDIO = "stdio";

    public static McpServerDefinition of(
            String id,
            String name,
            String transport,
            String endpoint,
            String command,
            List<String> args,
            Map<String, String> environment,
            String workingDirectory,
            String bearerToken,
            Boolean enabled
    ) {
        String normalizedId = normalizeId(id == null || id.isBlank() ? name : id);
        String normalizedTransport = normalizeTransport(transport, endpoint, command);
        URI uri = null;
        String normalizedCommand = command == null ? "" : command.trim();
        String normalizedWorkingDirectory = workingDirectory == null ? "" : workingDirectory.trim();

        if (TRANSPORT_HTTP.equals(normalizedTransport)) {
            uri = URI.create(endpoint == null ? "" : endpoint.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))) {
                throw new IllegalArgumentException("MCP endpoint must be an http(s) URL");
            }
        }
        if (TRANSPORT_STDIO.equals(normalizedTransport)) {
            if (normalizedCommand.isBlank()) {
                throw new IllegalArgumentException("MCP stdio command is required");
            }
            if (!normalizedWorkingDirectory.isBlank()) {
                Path.of(normalizedWorkingDirectory);
            }
        }
        return new McpServerDefinition(
                normalizedId,
                name == null || name.isBlank() ? normalizedId : name.trim(),
                normalizedTransport,
                uri,
                normalizedCommand,
                args == null ? List.of() : List.copyOf(args),
                environment == null ? Map.of() : Map.copyOf(environment),
                normalizedWorkingDirectory,
                bearerToken == null ? "" : bearerToken.trim(),
                enabled == null || enabled
        );
    }

    public String endpointText() {
        return endpoint == null ? "" : endpoint.toString();
    }

    public boolean isStdio() {
        return TRANSPORT_STDIO.equals(transport);
    }

    private static String normalizeId(String value) {
        String normalized = (value == null ? "" : value.trim())
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("MCP server id is required");
        }
        if (!SAFE_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("MCP server id must contain only letters, numbers, underscores, or dashes");
        }
        return normalized;
    }

    private static String normalizeTransport(String transport, String endpoint, String command) {
        String normalized = transport == null ? "" : transport.trim().toLowerCase(Locale.ROOT).replace("-", "_");
        if (normalized.isBlank()) {
            normalized = command != null && !command.isBlank() ? TRANSPORT_STDIO : TRANSPORT_HTTP;
        }
        if ("http".equals(normalized) || "streamablehttp".equals(normalized) || "streamable_http".equals(normalized)) {
            return TRANSPORT_HTTP;
        }
        if (TRANSPORT_STDIO.equals(normalized)) {
            return TRANSPORT_STDIO;
        }
        throw new IllegalArgumentException("Unsupported MCP transport: " + transport);
    }
}
