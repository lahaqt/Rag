package com.example.ragagent.config;

import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.RedisSaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Durable graph state required by Spring AI Alibaba HITL interrupts. */
@Configuration
public class GraphCheckpointConfig {
    @Bean(destroyMethod = "shutdown")
    public RedissonClient graphCheckpointRedissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password
    ) {
        Config config = new Config();
        var server = config.useSingleServer().setAddress("redis://" + host + ":" + port);
        if (password != null && !password.isBlank()) {
            server.setPassword(password);
        }
        return Redisson.create(config);
    }

    @Bean
    public SaverConfig agentGraphSaverConfig(RedissonClient graphCheckpointRedissonClient, ObjectMapper objectMapper) {
        return SaverConfig.builder().register(new RedisSaver(graphCheckpointRedissonClient, objectMapper)).build();
    }
}
