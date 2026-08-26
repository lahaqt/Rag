package com.example.authoringcoach.content.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain contentSecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/internal/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(authorities())))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${security.oidc.issuer-uri}") String issuer,
            @Value("${security.oidc.jwk-set-uri}") String jwkSetUri,
            @Value("${security.oidc.audience:content-service}") String audience,
            @Value("${security.oidc.authorized-client-id:authoring-service}") String authorizedClientId
    ) {
        var decoder = org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>("aud",
                audiences -> audiences != null && audiences.contains(audience));
        OAuth2TokenValidator<Jwt> clientValidator = token -> authorizedClientId.equals(token.getClaimAsString("azp"))
                || authorizedClientId.equals(token.getClaimAsString("client_id"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new org.springframework.security.oauth2.core.OAuth2Error(
                        "invalid_token", "Token is not authorized for the Authoring service client", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), audienceValidator, clientValidator));
        return decoder;
    }

    private Converter<Jwt, ? extends AbstractAuthenticationToken> authorities() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Set<GrantedAuthority> authorities = new LinkedHashSet<>();
            addRoles(authorities, jwt.getClaimAsStringList("roles"));
            Object realm = jwt.getClaim("realm_access");
            if (realm instanceof Map<?, ?> values && values.get("roles") instanceof Collection<?> roles) {
                addRoles(authorities, roles.stream().map(Object::toString).toList());
            }
            return authorities;
        });
        return converter;
    }

    private void addRoles(Set<GrantedAuthority> authorities, List<String> roles) {
        if (roles == null) return;
        roles.stream().filter(role -> role != null && !role.isBlank())
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                .forEach(authorities::add);
    }
}
