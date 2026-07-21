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
    private static final String SECRET = "knowledge-test-secret";

    @Test
    void blocksUnsignedKnowledgeApiRequestsAndAllowsSignedOnes() throws Exception {
        GatewayIdentityFilter filter = new GatewayIdentityFilter(SECRET);
        MockHttpServletRequest unsigned = new MockHttpServletRequest("GET", "/api/knowledge-bases");
        MockHttpServletResponse unsignedResponse = new MockHttpServletResponse();
        filter.doFilter(unsigned, unsignedResponse, new MockFilterChain());
        assertThat(unsignedResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest signed = new MockHttpServletRequest("GET", "/api/knowledge-bases");
        signed.addHeader("X-Rag-User-Id", "agent-service");
        signed.addHeader("X-Rag-Identity-Signature", signature("agent-service"));
        MockHttpServletResponse signedResponse = new MockHttpServletResponse();
        filter.doFilter(signed, signedResponse, new MockFilterChain());
        assertThat(signedResponse.getStatus()).isEqualTo(200);
    }

    private String signature(String identity) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(identity.getBytes(StandardCharsets.UTF_8)));
    }
}
