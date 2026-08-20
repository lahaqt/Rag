package com.example.ragagent.config;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RuntimeModelConfigurationService {
    public record ModelProfileRequest(String name, String protocol, String baseUrl, String model, String apiKey,
                                      Double temperature, Integer maxTokens, Boolean enabled) {
    }
    public record ModelProfileResponse(String id, String name, String protocol, String baseUrl, String model,
                                       boolean apiKeyConfigured, String apiKeyHint, double temperature, int maxTokens,
                                       boolean enabled, boolean active, Instant updatedAt) {
    }
    public record ModelTestResult(boolean success, String message) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final ConfigSecretCipher cipher;
    private final RagProperties properties;
    private volatile RagProperties.Llm active;

    public RuntimeModelConfigurationService(JdbcTemplate jdbcTemplate, ConfigSecretCipher cipher, RagProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.cipher = cipher;
        this.properties = properties;
        initializeSchema();
        refreshActive();
    }

    public RagProperties.Llm activeConfiguration() { return active == null ? properties.llm() : active; }

    public List<ModelProfileResponse> list() {
        return jdbcTemplate.query("SELECT * FROM runtime_model_profiles ORDER BY active DESC, updated_at DESC", (rs, row) -> response(rs.getString("id"), rs.getString("name"), rs.getString("protocol"), rs.getString("base_url"), rs.getString("model"), rs.getString("api_key_ciphertext"), rs.getDouble("temperature"), rs.getInt("max_tokens"), rs.getBoolean("enabled"), rs.getBoolean("active"), rs.getTimestamp("updated_at").toInstant()));
    }

    public ModelProfileResponse create(ModelProfileRequest request) {
        validate(request);
        String id = "model-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO runtime_model_profiles (id, name, protocol, base_url, model, api_key_ciphertext, temperature, max_tokens, enabled, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, false)
                """, id, request.name().trim(), protocol(request.protocol()), request.baseUrl().trim(), request.model().trim(), cipher.encrypt(request.apiKey()),
                temperature(request.temperature()), maxTokens(request.maxTokens()), Boolean.TRUE.equals(request.enabled()));
        return profile(id);
    }

    public ModelProfileResponse update(String id, ModelProfileRequest request) {
        ModelRow current = row(id);
        String apiCipher = request.apiKey() == null || request.apiKey().isBlank() ? current.apiKeyCiphertext() : cipher.encrypt(request.apiKey());
        ModelProfileRequest merged = new ModelProfileRequest(request.name() == null ? current.name() : request.name(),
                request.protocol() == null ? current.protocol() : request.protocol(), request.baseUrl() == null ? current.baseUrl() : request.baseUrl(),
                request.model() == null ? current.model() : request.model(), request.apiKey(), request.temperature() == null ? current.temperature() : request.temperature(),
                request.maxTokens() == null ? current.maxTokens() : request.maxTokens(), request.enabled() == null ? current.enabled() : request.enabled());
        validate(merged);
        jdbcTemplate.update("""
                UPDATE runtime_model_profiles SET name=?, protocol=?, base_url=?, model=?, api_key_ciphertext=?, temperature=?, max_tokens=?, enabled=?, updated_at=now()
                WHERE id=?
                """, merged.name().trim(), protocol(merged.protocol()), merged.baseUrl().trim(), merged.model().trim(), apiCipher,
                temperature(merged.temperature()), maxTokens(merged.maxTokens()), merged.enabled(), id);
        refreshActive();
        return profile(id);
    }

    public void delete(String id) {
        ModelRow profile = row(id);
        if (profile.active()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Activate another model profile before deleting this one");
        jdbcTemplate.update("DELETE FROM runtime_model_profiles WHERE id=?", id);
    }

    public ModelTestResult test(String id) {
        ModelRow profile = row(id);
        try {
            URI uri = URI.create(profile.baseUrl());
            if (uri.getUserInfo() != null || uri.getHost() == null) throw new IllegalArgumentException("The model URL must be an absolute endpoint without embedded credentials");
            if (profile.apiKeyCiphertext().isBlank()) return new ModelTestResult(false, "An API key is required before this profile can be activated.");
            return new ModelTestResult(true, "Configuration format is valid. Activate the profile to use it for new requests.");
        } catch (Exception exception) {
            return new ModelTestResult(false, "Invalid model endpoint: " + exception.getMessage());
        }
    }

    public ModelProfileResponse activate(String id) {
        ModelTestResult test = test(id);
        if (!test.success()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, test.message());
        jdbcTemplate.update("UPDATE runtime_model_profiles SET active=false WHERE active=true");
        jdbcTemplate.update("UPDATE runtime_model_profiles SET active=true, enabled=true, updated_at=now() WHERE id=?", id);
        refreshActive();
        return profile(id);
    }

    private void refreshActive() {
        List<ModelRow> rows = jdbcTemplate.query("SELECT * FROM runtime_model_profiles WHERE active=true AND enabled=true", (rs, row) -> new ModelRow(rs.getString("id"), rs.getString("name"), rs.getString("protocol"), rs.getString("base_url"), rs.getString("model"), rs.getString("api_key_ciphertext"), rs.getDouble("temperature"), rs.getInt("max_tokens"), rs.getBoolean("enabled"), rs.getBoolean("active")));
        if (rows.isEmpty()) { active = properties.llm(); return; }
        ModelRow row = rows.get(0);
        String apiKey = cipher.decrypt(row.apiKeyCiphertext());
        active = "ANTHROPIC_COMPATIBLE".equals(row.protocol())
                ? new RagProperties.Llm("runtime", row.model(), apiKey, row.temperature(), row.maxTokens(), new RagProperties.CompatibleEndpoint(""), new RagProperties.CompatibleEndpoint(row.baseUrl()))
                : new RagProperties.Llm("runtime", row.model(), apiKey, row.temperature(), row.maxTokens(), new RagProperties.CompatibleEndpoint(row.baseUrl()), new RagProperties.CompatibleEndpoint(""));
    }

    private ModelProfileResponse profile(String id) { ModelRow row = row(id); return response(row.id(), row.name(), row.protocol(), row.baseUrl(), row.model(), row.apiKeyCiphertext(), row.temperature(), row.maxTokens(), row.enabled(), row.active(), Instant.now()); }
    private ModelRow row(String id) {
        List<ModelRow> rows = jdbcTemplate.query("SELECT * FROM runtime_model_profiles WHERE id=?", (rs, row) -> new ModelRow(rs.getString("id"), rs.getString("name"), rs.getString("protocol"), rs.getString("base_url"), rs.getString("model"), rs.getString("api_key_ciphertext"), rs.getDouble("temperature"), rs.getInt("max_tokens"), rs.getBoolean("enabled"), rs.getBoolean("active")), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Model profile not found"); return rows.get(0);
    }
    private ModelProfileResponse response(String id, String name, String protocol, String baseUrl, String model, String apiCipher, double temp, int tokens, boolean enabled, boolean active, Instant updatedAt) {
        String plain = apiCipher == null || apiCipher.isBlank() ? "" : cipher.decrypt(apiCipher);
        String hint = plain.length() < 5 ? "Configured" : "••••" + plain.substring(plain.length() - 4);
        return new ModelProfileResponse(id, name, protocol, baseUrl, model, !plain.isBlank(), hint, temp, tokens, enabled, active, updatedAt);
    }
    private void validate(ModelProfileRequest request) {
        if (request == null || blank(request.name()) || blank(request.baseUrl()) || blank(request.model())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name, base URL, and model are required");
        String protocol = protocol(request.protocol());
        if (!"OPENAI_COMPATIBLE".equals(protocol) && !"ANTHROPIC_COMPATIBLE".equals(protocol)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported model protocol");
        URI uri = URI.create(request.baseUrl().trim()); if (uri.getHost() == null || uri.getUserInfo() != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use an absolute model URL without embedded credentials");
    }
    private String protocol(String value) { return value == null || value.isBlank() ? "OPENAI_COMPATIBLE" : value.trim().toUpperCase(); }
    private double temperature(Double value) { return value == null ? 0.2 : Math.max(0, Math.min(value, 1.5)); }
    private int maxTokens(Integer value) { return value == null ? 1200 : Math.max(128, Math.min(value, 8000)); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private void initializeSchema() { jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS runtime_model_profiles (
                id VARCHAR(128) PRIMARY KEY, name VARCHAR(200) NOT NULL UNIQUE, protocol VARCHAR(64) NOT NULL, base_url TEXT NOT NULL,
                model VARCHAR(200) NOT NULL, api_key_ciphertext TEXT NOT NULL DEFAULT '', temperature DOUBLE PRECISION NOT NULL,
                max_tokens INT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT true, active BOOLEAN NOT NULL DEFAULT false,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(), updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now())
            """); }
    private record ModelRow(String id, String name, String protocol, String baseUrl, String model, String apiKeyCiphertext, double temperature, int maxTokens, boolean enabled, boolean active) { }
}
