package com.example.ragagent.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ApprovalServiceTests {
    private ApprovalService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:approval-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        service = new ApprovalService(new JdbcTemplate(dataSource), new ObjectMapper());
    }

    @Test
    void writeApprovalIsIdempotentAndAllowsOnlyOneExecutionLease() {
        ApprovalRequest first = service.createWriteApproval("user-1", "conversation-1", "run-1", "step-1", "function.refund", Map.of("orderId", "A-1"));
        ApprovalRequest repeated = service.createWriteApproval("user-1", "conversation-1", "run-1", "step-1", "function.refund", Map.of("orderId", "A-1"));

        assertThat(repeated.id()).isEqualTo(first.id());
        ApprovalRequest decided = service.decide(first.id(), "user-1", new ApprovalDecisionRequest(ApprovalDecision.APPROVE, null, "", first.version()));
        assertThat(decided.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(service.claimExecution(first.id())).isTrue();
        assertThat(service.claimExecution(first.id())).isFalse();
        service.completeExecution(first.id());
        assertThat(service.get(first.id(), "user-1").status()).isEqualTo(ApprovalStatus.EXECUTED);
    }

    @Test
    void decisionRequiresOwnerAndMatchingVersion() {
        ApprovalRequest approval = service.createMemoryPreferenceApproval("user-1", "conversation-1", "memory-1", "User preference: concise", Map.of());

        assertThatThrownBy(() -> service.decide(approval.id(), "user-2", new ApprovalDecisionRequest(ApprovalDecision.APPROVE, null, "", approval.version())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.decide(approval.id(), "user-1", new ApprovalDecisionRequest(ApprovalDecision.APPROVE, null, "", approval.version() + 1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
