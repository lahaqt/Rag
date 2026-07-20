package com.example.ragagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragagent.memory.InMemorySemanticMemoryStore;
import com.example.ragagent.memory.SemanticMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AgentServiceApplicationTests {
    @Autowired
    private SemanticMemoryStore semanticMemoryStore;

    @Test
    void contextLoads() {
        assertThat(semanticMemoryStore).isInstanceOf(InMemorySemanticMemoryStore.class);
    }
}
