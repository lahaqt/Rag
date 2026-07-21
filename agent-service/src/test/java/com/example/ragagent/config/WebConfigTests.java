package com.example.ragagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;

class WebConfigTests {
    @Test
    void chatExecutorUsesBoundedWorkersAndQueue() {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) new WebConfig(null).chatExecutor();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(4);
            assertThat(executor.getMaximumPoolSize()).isEqualTo(8);
            assertThat(executor.getQueue().remainingCapacity()).isEqualTo(100);
        } finally {
            executor.shutdownNow();
        }
    }
}
