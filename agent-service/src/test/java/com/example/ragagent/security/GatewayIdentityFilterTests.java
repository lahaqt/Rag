package com.example.ragagent.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class GatewayIdentityFilterTests {
    private static final String SECRET = "test-gateway-signing-secret";

    @Test
    void failsClosedWhenGatewaySigningIsNotConfigured() throws Exception {
        MockHttpServletRequest request = protectedRequest("user-1", signature("user-1"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        new GatewayIdentityFilter("", "admin").doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void rejectsForgedIdentityAndAcceptsOnlySignedIdentity() throws Exception {
        GatewayIdentityFilter filter = new GatewayIdentityFilter(SECRET, "admin");
        MockHttpServletRequest forged = protectedRequest("victim", "not-a-valid-signature");
        MockHttpServletResponse forgedResponse = new MockHttpServletResponse();

        filter.doFilter(forged, forgedResponse, new MockFilterChain());

        assertThat(forgedResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest signed = protectedRequest("user-1", signature("user-1"));
        MockHttpServletResponse signedResponse = new MockHttpServletResponse();
        filter.doFilter(signed, signedResponse, new MockFilterChain());

        assertThat(signedResponse.getStatus()).isEqualTo(200);
        assertThat(signed.getAttribute(RequestIdentity.USER_ID_ATTRIBUTE)).isEqualTo("user-1");
    }

    @Test
    void limitsMcpAdministrationToConfiguredAdminIdentities() throws Exception {
        GatewayIdentityFilter filter = new GatewayIdentityFilter(SECRET, "admin");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/mcp/servers");
        request.addHeader("X-Rag-User-Id", "user-1");
        request.addHeader("X-Rag-Identity-Signature", signature("user-1"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    private static MockHttpServletRequest protectedRequest(String userId, String signature) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/approvals");
        request.addHeader("X-Rag-User-Id", userId);
        request.addHeader("X-Rag-Identity-Signature", signature);
        return request;
    }

    private static String signature(String userId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(userId.getBytes(StandardCharsets.UTF_8)));
    }
}
