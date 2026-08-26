package com.example.authoringcoach.client;

import com.example.authoringcoach.config.AuthoringProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class ServiceAccessTokenProvider {
    private final AuthoringProperties.ServiceClient configuration;
    private final RestClient restClient;
    private volatile CachedToken cached;

    public ServiceAccessTokenProvider(AuthoringProperties properties, RestClient.Builder builder) {
        this.configuration = properties.serviceClient();
        this.restClient = builder.clone().build();
    }

    public synchronized String token() {
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(15))) return cached.value();
        if (configuration.clientSecret().isBlank()) {
            throw new IllegalStateException("AUTHORING_CONTENT_CLIENT_SECRET is required for Content Service access");
        }
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", configuration.clientId());
        form.add("client_secret", configuration.clientSecret());
        form.add("audience", configuration.audience());
        TokenResponse response = restClient.post().uri(configuration.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(TokenResponse.class);
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new IllegalStateException("OIDC provider returned no service access token");
        }
        cached = new CachedToken(response.accessToken(), Instant.now().plusSeconds(Math.max(30, response.expiresIn())));
        return cached.value();
    }

    private record TokenResponse(@JsonProperty("access_token") String accessToken,
                                 @JsonProperty("expires_in") long expiresIn) { }
    private record CachedToken(String value, Instant expiresAt) { }
}
