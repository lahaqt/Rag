package com.example.ragagent.memory;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MemoryProjectionOutboxClaimServiceTests {

    @Test
    void recoversExpiredLeasesBeforeClaimingPendingEvents() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(contains("WITH claimable"), anyInt()))
                .thenReturn(List.of(Map.of("id", 1L, "attempts", 0, "payload", "{}")));

        List<Map<String, Object>> claimed = new MemoryProjectionOutboxClaimService(jdbcTemplate).claimBatch(20);

        verify(jdbcTemplate).update(contains("projection lease expired"));
        verify(jdbcTemplate).queryForList(contains("WITH claimable"), eq(20));
        org.assertj.core.api.Assertions.assertThat(claimed).hasSize(1);
    }
}
