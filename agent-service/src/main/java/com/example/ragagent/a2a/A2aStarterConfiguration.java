package com.example.ragagent.a2a;

import com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties;
import com.alibaba.cloud.ai.a2a.core.server.A2aServerExecutorProvider;
import com.alibaba.cloud.ai.a2a.core.server.DefaultA2aServerExecutorProvider;
import com.alibaba.cloud.ai.a2a.core.server.JsonRpcA2aRequestHandler;
import com.example.ragagent.service.MultiAgentOrchestrator;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.events.InMemoryQueueManager;
import io.a2a.server.events.QueueManager;
import io.a2a.server.requesthandlers.DefaultRequestHandler;
import io.a2a.server.requesthandlers.JSONRPCHandler;
import io.a2a.server.requesthandlers.RequestHandler;
import io.a2a.server.tasks.BasePushNotificationSender;
import io.a2a.server.tasks.InMemoryPushNotificationConfigStore;
import io.a2a.server.tasks.InMemoryTaskStore;
import io.a2a.server.tasks.PushNotificationConfigStore;
import io.a2a.server.tasks.PushNotificationSender;
import io.a2a.server.tasks.TaskStore;
import io.a2a.spec.AgentCard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class A2aStarterConfiguration {
    @Bean
    @ConditionalOnMissingBean
    AgentCard agentCard(A2aAgentRegistry registry, A2aServerProperties properties) {
        return registry.orchestratorCard(properties.getMessageUrl());
    }

    @Bean
    @ConditionalOnMissingBean
    AgentExecutor agentExecutor(MultiAgentOrchestrator multiAgentOrchestrator) {
        return new RagA2aAgentExecutor(multiAgentOrchestrator);
    }

    @Bean
    @ConditionalOnMissingBean
    A2aServerExecutorProvider a2aServerExecutorProvider() {
        return new DefaultA2aServerExecutorProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    TaskStore taskStore() {
        return new InMemoryTaskStore();
    }

    @Bean
    @ConditionalOnMissingBean
    QueueManager queueManager() {
        return new InMemoryQueueManager();
    }

    @Bean
    @ConditionalOnMissingBean
    PushNotificationConfigStore pushConfigStore() {
        return new InMemoryPushNotificationConfigStore();
    }

    @Bean
    @ConditionalOnMissingBean
    PushNotificationSender pushSender(PushNotificationConfigStore pushConfigStore) {
        return new BasePushNotificationSender(pushConfigStore);
    }

    @Bean
    @ConditionalOnMissingBean
    RequestHandler requestHandler(
            AgentExecutor agentExecutor,
            TaskStore taskStore,
            QueueManager queueManager,
            PushNotificationConfigStore pushConfigStore,
            PushNotificationSender pushSender,
            A2aServerExecutorProvider executorProvider
    ) {
        return new DefaultRequestHandler(
                agentExecutor,
                taskStore,
                queueManager,
                pushConfigStore,
                pushSender,
                executorProvider.getA2aServerExecutor()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    JSONRPCHandler jsonrpcHandler(AgentCard agentCard, RequestHandler requestHandler) {
        return new JSONRPCHandler(agentCard, requestHandler);
    }

    @Bean
    @ConditionalOnMissingBean
    JsonRpcA2aRequestHandler jsonRpcA2aRequestHandler(JSONRPCHandler jsonrpcHandler) {
        return new JsonRpcA2aRequestHandler(jsonrpcHandler);
    }
}
