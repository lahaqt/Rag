package com.example.authoringcoach.mcp;

import com.example.authoringcoach.config.AuthoringProperties;
import com.example.authoringcoach.config.ConfigSecretCipher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class McpServerService {
    private static final Logger log = LoggerFactory.getLogger(McpServerService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AuthoringProperties properties;
    private final McpSdkClient mcpSdkClient;
    private final ObjectMapper objectMapper;
    private final McpAccessPolicy mcpAccessPolicy;
    private final JdbcTemplate jdbcTemplate;
    private final ConfigSecretCipher secretCipher;
    private final Map<String, ManagedMcpServer> servers = new ConcurrentHashMap<>();

    @Autowired
    public McpServerService(
            AuthoringProperties properties,
            McpSdkClient mcpSdkClient,
            ObjectMapper objectMapper,
            McpAccessPolicy mcpAccessPolicy,
            JdbcTemplate jdbcTemplate,
            ConfigSecretCipher secretCipher
    ) {
        this.properties = properties;
        this.mcpSdkClient = mcpSdkClient;
        this.objectMapper = objectMapper;
        this.mcpAccessPolicy = mcpAccessPolicy;
        this.jdbcTemplate = jdbcTemplate;
        this.secretCipher = secretCipher;
    }

    /** Compatibility constructor retained for focused unit tests that exercise only in-memory built-in servers. */
    public McpServerService(AuthoringProperties properties, McpSdkClient mcpSdkClient, ObjectMapper objectMapper,
                            McpAccessPolicy mcpAccessPolicy) {
        this.properties = properties;
        this.mcpSdkClient = mcpSdkClient;
        this.objectMapper = objectMapper;
        this.mcpAccessPolicy = mcpAccessPolicy;
        this.jdbcTemplate = null;
        this.secretCipher = null;
    }

    @PostConstruct
    public void loadConfiguredServers() {
        for (AuthoringProperties.McpServer server : properties.mcp().servers()) {
            McpServerDefinition definition = McpServerDefinition.of(
                    server.id(),
                    server.name(),
                    server.transport(),
                    server.endpoint(),
                    server.command(),
                    server.args(),
                    server.environment(),
                    server.workingDirectory(),
                    server.bearerToken(),
                    server.enabled(),
                    server.readOnly()
            );
            servers.put(definition.id(), new ManagedMcpServer(definition));
        }
        loadRuntimeServers();
    }

    public List<McpServerResponse> listServers() {
        return servers.values().stream()
                .sorted(Comparator.comparing(server -> server.definition().name()))
                .map(ManagedMcpServer::toResponse)
                .toList();
    }

    public McpServerResponse upsert(McpServerRequest request) {
        ManagedMcpServer previous = request.id() == null || request.id().isBlank() ? null : servers.get(request.id());
        if (previous != null && previous.definition().readOnly()) {
            throw new IllegalStateException("Built-in MCP server cannot be modified: " + previous.definition().id());
        }
        String bearerToken = request.bearerToken();
        if ((bearerToken == null || bearerToken.isBlank()) && previous != null) {
            bearerToken = previous.definition().bearerToken();
        }
        McpServerDefinition definition = McpServerDefinition.of(
                request.id(),
                request.name(),
                request.transport(),
                request.endpoint(),
                request.command(),
                request.args(),
                request.environment(),
                request.workingDirectory(),
                bearerToken,
                request.enabled(),
                false
        );
        ManagedMcpServer existingByDefinitionId = servers.get(definition.id());
        if (existingByDefinitionId != null && existingByDefinitionId.definition().readOnly()) {
            throw new IllegalStateException("Built-in MCP server cannot be modified: " + definition.id());
        }
        mcpAccessPolicy.requireRuntimeRegistrationAllowed(definition);
        ManagedMcpServer next = new ManagedMcpServer(definition);
        if (definition.enabled()) {
            try {
                refreshServer(next);
            } catch (Exception exception) {
                next.close();
                throw new IllegalArgumentException(
                        "MCP server validation failed for " + definition.transport() + " "
                                + (definition.isStdio() ? definition.command() : definition.endpointText())
                                + ": " + rootMessage(exception),
                        exception
                );
            }
        }
        previous = servers.put(definition.id(), next);
        if (previous != null) {
            previous.close();
        }
        persistRuntimeServer(definition);
        return next.toResponse();
    }

    public void delete(String id) {
        ManagedMcpServer existing = servers.get(id);
        if (existing == null) {
            throw new IllegalArgumentException("Unknown MCP server: " + id);
        }
        if (existing.definition().readOnly()) {
            throw new IllegalStateException("Built-in MCP server cannot be deleted: " + id);
        }
        ManagedMcpServer removed = servers.remove(id);
        if (removed == null) {
            throw new IllegalArgumentException("Unknown MCP server: " + id);
        }
        removed.close();
        if (jdbcTemplate != null) jdbcTemplate.update("DELETE FROM runtime_mcp_servers WHERE id=?", id);
    }

    public McpServerResponse refresh(String id) {
        ManagedMcpServer server = requireServer(id);
        return refreshAndReturn(server);
    }

    @Scheduled(fixedDelayString = "${authoring.mcp.health-check-interval-ms:30000}", initialDelayString = "${authoring.mcp.health-check-initial-delay-ms:10000}")
    public void superviseServerStatus() {
        for (ManagedMcpServer server : servers.values()) {
            if (server.definition().enabled()) {
                refreshAndReturn(server);
            }
        }
    }

    private McpServerResponse refreshAndReturn(ManagedMcpServer server) {
        try {
            refreshServer(server);
        } catch (Exception exception) {
            server.close();
            server.markError(rootMessage(exception));
        }
        return server.toResponse();
    }

    private void refreshServer(ManagedMcpServer server) {
        List<McpToolDescriptor> tools = mcpSdkClient.listTools(server);
        server.markHealthy(tools);
    }

    public McpToolCallResponse callTool(String id, String toolName, Map<String, Object> arguments) {
        ManagedMcpServer server = requireServer(id);
        return mcpSdkClient.callTool(server, toolName, arguments);
    }

    public boolean hasEnabledTools() {
        return servers.values().stream().anyMatch(server -> server.definition().enabled() && !server.tools().isEmpty());
    }

    public Optional<McpToolSelection> selectTool(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isBlank()) {
            return Optional.empty();
        }

        Optional<McpToolSelection> commandSelection = selectFromCommand(trimmed);
        if (commandSelection.isPresent()) {
            return commandSelection;
        }

        String normalizedQuery = normalize(trimmed);
        McpToolSelection bestSelection = null;
        int bestScore = 0;
        for (ManagedMcpServer server : servers.values()) {
            if (!server.definition().enabled()) {
                continue;
            }
            for (McpToolDescriptor tool : server.tools()) {
                int score = scoreTool(normalizedQuery, tool);
                if (score > bestScore) {
                    bestScore = score;
                    bestSelection = new McpToolSelection(
                            server.definition().id(),
                            tool.name(),
                            tool,
                            inferArguments(tool, trimmed),
                            "matched_mcp_tool:" + tool.name()
                    );
                }
            }
        }
        return bestScore > 0 ? Optional.of(bestSelection) : Optional.empty();
    }

    /** Selects only tools explicitly allowed by a course binding. Explicit /mcp commands are not accepted here. */
    public Optional<McpToolSelection> selectTool(String query, Map<String, java.util.Set<String>> allowedToolsByServer) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isBlank() || allowedToolsByServer == null || allowedToolsByServer.isEmpty()) {
            return Optional.empty();
        }
        String normalizedQuery = normalize(trimmed);
        McpToolSelection bestSelection = null;
        int bestScore = 0;
        for (ManagedMcpServer server : servers.values()) {
            java.util.Set<String> allowedTools = allowedToolsByServer.get(server.definition().id());
            if (!server.definition().enabled() || allowedTools == null || allowedTools.isEmpty()) {
                continue;
            }
            for (McpToolDescriptor tool : server.tools()) {
                if (!allowedTools.contains(tool.name())) {
                    continue;
                }
                int score = scoreTool(normalizedQuery, tool);
                if (score > bestScore) {
                    bestScore = score;
                    bestSelection = new McpToolSelection(
                            server.definition().id(), tool.name(), tool, inferArguments(tool, trimmed),
                            "course_tool_whitelist:" + tool.name()
                    );
                }
            }
        }
        return bestScore > 0 ? Optional.of(bestSelection) : Optional.empty();
    }

    /**
     * Finds a not-yet-executed tool whose identifier-shaped required input can
     * be supplied by a prior structured observation.
     */
    public Optional<McpToolSelection> selectNextTool(
            String query,
            Map<String, Object> observation,
            java.util.Set<String> executedToolKeys
    ) {
        String normalizedQuery = normalize(query);
        McpToolSelection bestSelection = null;
        int bestScore = 0;
        for (ManagedMcpServer server : servers.values()) {
            if (!server.definition().enabled()) {
                continue;
            }
            for (McpToolDescriptor tool : server.tools()) {
                String key = server.definition().id() + "." + tool.name();
                if (executedToolKeys != null && executedToolKeys.contains(key)) {
                    continue;
                }
                ToolArgumentBinder.BoundArguments bound = ToolArgumentBinder.bind(
                        tool, inferArguments(tool, query), observation
                );
                if (!ToolArgumentBinder.requiredIdentifiersBound(tool, bound.boundProperties())) {
                    continue;
                }
                int score = scoreTool(normalizedQuery, tool) + bound.boundProperties().size() * 10;
                if (score > bestScore) {
                    bestScore = score;
                    bestSelection = new McpToolSelection(
                            server.definition().id(), tool.name(), tool, bound.arguments(),
                            "observation_bound_mcp_chain:" + String.join(",", bound.boundProperties())
                    );
                }
            }
        }
        return Optional.ofNullable(bestSelection);
    }

    public McpToolCallResponse callSelection(McpToolSelection selection) {
        return callTool(selection.serverId(), selection.toolName(), selection.arguments());
    }

    private Optional<McpToolSelection> selectFromCommand(String query) {
        if (!query.startsWith("/mcp ")) {
            return Optional.empty();
        }
        String rest = query.substring("/mcp ".length()).trim();
        int firstSpace = rest.indexOf(' ');
        String target = firstSpace >= 0 ? rest.substring(0, firstSpace) : rest;
        String argumentsText = firstSpace >= 0 ? rest.substring(firstSpace + 1).trim() : "{}";
        String serverId = "";
        String toolName = target;
        int dot = target.indexOf('.');
        if (dot >= 0) {
            serverId = target.substring(0, dot);
            toolName = target.substring(dot + 1);
        }

        for (ManagedMcpServer server : servers.values()) {
            if (!server.definition().enabled()) {
                continue;
            }
            if (!serverId.isBlank() && !server.definition().id().equals(serverId)) {
                continue;
            }
            for (McpToolDescriptor tool : server.tools()) {
                if (tool.name().equals(toolName)) {
                    return Optional.of(new McpToolSelection(
                            server.definition().id(),
                            tool.name(),
                            tool,
                            parseArguments(argumentsText),
                            "explicit_mcp_command"
                    ));
                }
            }
        }
        return Optional.empty();
    }

    private Map<String, Object> parseArguments(String value) {
        String json = value == null || value.isBlank() ? "{}" : value;
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) {
                throw new IllegalArgumentException("MCP arguments must be a JSON object");
            }
            return objectMapper.convertValue(node, MAP_TYPE);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid MCP arguments JSON: " + exception.getMessage(), exception);
        }
    }

    private Map<String, Object> inferArguments(McpToolDescriptor tool, String query) {
        JsonNode schema = tool.inputSchema();
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (schema == null || schema.isNull() || !schema.has("properties")) {
            arguments.put("query", query);
            return arguments;
        }

        JsonNode properties = schema.get("properties");
        List<String> required = schema.has("required") && schema.get("required").isArray()
                ? objectMapper.convertValue(schema.get("required"), new TypeReference<>() {
                })
                : List.of();

        String preferredString = preferredStringProperty(properties, required);
        if (!preferredString.isBlank()) {
            arguments.put(preferredString, query);
        }

        for (String name : required) {
            if (arguments.containsKey(name)) {
                continue;
            }
            JsonNode property = properties.get(name);
            String type = property == null ? "" : property.path("type").asText("");
            if ("string".equals(type)) {
                arguments.put(name, query);
            } else if ("integer".equals(type) || "number".equals(type)) {
                arguments.put(name, defaultNumber(name));
            } else if ("boolean".equals(type)) {
                arguments.put(name, true);
            }
        }

        if (arguments.isEmpty()) {
            arguments.put("query", query);
        }
        return arguments;
    }

    private String preferredStringProperty(JsonNode properties, List<String> required) {
        for (String preferred : List.of("query", "input", "text", "question", "prompt")) {
            JsonNode property = properties.get(preferred);
            if (property != null && "string".equals(property.path("type").asText(""))) {
                return preferred;
            }
        }
        for (String name : required) {
            JsonNode property = properties.get(name);
            if (property != null && "string".equals(property.path("type").asText(""))) {
                return name;
            }
        }
        return "";
    }

    private int defaultNumber(String name) {
        String normalized = normalize(name);
        if (normalized.contains("top") || normalized.contains("limit") || normalized.equals("k")) {
            return 5;
        }
        return 1;
    }

    private int scoreTool(String normalizedQuery, McpToolDescriptor tool) {
        int score = 0;
        String name = normalize(tool.name());
        String title = normalize(tool.title());
        String description = normalize(tool.description());
        if (!name.isBlank() && normalizedQuery.contains(name)) {
            score += 8;
        }
        if (!title.isBlank() && normalizedQuery.contains(title)) {
            score += 5;
        }
        for (String token : name.split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (token.length() >= 4 && normalizedQuery.contains(token)) {
                score += 3;
            }
        }
        for (String token : description.split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (token.length() >= 4 && normalizedQuery.contains(token)) {
                score += 1;
            }
        }
        return score;
    }

    private ManagedMcpServer requireServer(String id) {
        ManagedMcpServer server = servers.get(id);
        if (server == null) {
            throw new IllegalArgumentException("Unknown MCP server: " + id);
        }
        return server;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private String rootMessage(Throwable throwable) {
        StringBuilder message = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            String currentMessage = current.getMessage();
            if (currentMessage != null && !currentMessage.isBlank()) {
                if (!message.isEmpty()) {
                    message.append(" -> ");
                }
                message.append(currentMessage.trim());
            }
            current = current.getCause();
        }
        return message.isEmpty() ? throwable.getClass().getSimpleName() : message.toString();
    }

    private void loadRuntimeServers() {
        if (jdbcTemplate == null || secretCipher == null) return;
        jdbcTemplate.query("SELECT * FROM runtime_mcp_servers", rs -> {
            try {
                List<String> args = objectMapper.readValue(rs.getString("args_json"), new TypeReference<List<String>>() {});
                Map<String, String> environment = rs.getString("environment_ciphertext").isBlank() ? Map.of()
                        : objectMapper.readValue(secretCipher.decrypt(rs.getString("environment_ciphertext")), new TypeReference<Map<String, String>>() {});
                McpServerDefinition definition = McpServerDefinition.of(rs.getString("id"), rs.getString("name"), rs.getString("transport"),
                        rs.getString("endpoint"), rs.getString("command"), args, environment, rs.getString("working_directory"),
                        secretCipher.decrypt(rs.getString("bearer_token_ciphertext")), rs.getBoolean("enabled"), false);
                servers.putIfAbsent(definition.id(), new ManagedMcpServer(definition));
            } catch (Exception exception) {
                log.warn("Runtime MCP server could not be restored. id={} error={}", rs.getString("id"), exception.getMessage());
            }
        });
    }

    private void persistRuntimeServer(McpServerDefinition definition) {
        if (jdbcTemplate == null || secretCipher == null) return;
        if (definition.readOnly()) return;
        try {
            String args = objectMapper.writeValueAsString(definition.args());
            String environment = definition.environment().isEmpty() ? "" : secretCipher.encrypt(objectMapper.writeValueAsString(definition.environment()));
            String bearer = secretCipher.encrypt(definition.bearerToken());
            int updated = jdbcTemplate.update("""
                    UPDATE runtime_mcp_servers SET name=?, transport=?, endpoint=?, command=?, args_json=?, environment_ciphertext=?,
                        working_directory=?, bearer_token_ciphertext=?, enabled=?, updated_at=now() WHERE id=?
                    """, definition.name(), definition.transport(), definition.endpointText(), definition.command(), args, environment,
                    definition.workingDirectory(), bearer, definition.enabled(), definition.id());
            if (updated == 0) jdbcTemplate.update("""
                    INSERT INTO runtime_mcp_servers (id, name, transport, endpoint, command, args_json, environment_ciphertext,
                        working_directory, bearer_token_ciphertext, enabled) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, definition.id(), definition.name(), definition.transport(), definition.endpointText(), definition.command(), args,
                    environment, definition.workingDirectory(), bearer, definition.enabled());
        } catch (Exception exception) {
            throw new IllegalStateException("Runtime MCP configuration could not be persisted", exception);
        }
    }
}
