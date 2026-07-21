package com.example.ragagent.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.example.ragagent.approval.ApprovalService;
import com.example.ragagent.history.ConversationHistoryService;
import com.example.ragagent.history.UpdateConversationRequest;
import com.example.ragagent.memory.MemoryGovernanceService;
import com.example.ragagent.security.RequestIdentity;
import com.example.ragagent.service.SpringAiAlibabaAgentRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class ProtectedUserControllerTests {

    @Test
    void rejectsCrossUserApprovalReadsBeforeCallingTheService() {
        ApprovalController controller = new ApprovalController(mock(ApprovalService.class), mock(SpringAiAlibabaAgentRuntime.class), mock(MemoryGovernanceService.class));

        assertForbidden(() -> controller.get(authenticatedRequest("alice"), "approval-1", "bob"));
    }

    @Test
    void rejectsCrossUserMemoryDeletionBeforeCallingTheService() {
        MemoryController controller = new MemoryController(mock(MemoryGovernanceService.class));

        assertForbidden(() -> controller.forgetUser(authenticatedRequest("alice"), "bob"));
    }

    @Test
    void rejectsCrossUserConversationUpdatesBeforeCallingTheService() {
        ConversationController controller = new ConversationController(mock(ConversationHistoryService.class));

        assertForbidden(() -> controller.update(
                authenticatedRequest("alice"), "conversation-1", new UpdateConversationRequest("bob", "title", null, null, null, null)
        ));
    }

    private static MockHttpServletRequest authenticatedRequest(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdentity.USER_ID_ATTRIBUTE, userId);
        return request;
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
