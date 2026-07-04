package com.example.ragagent.service;

import com.example.ragagent.a2a.A2aRuntime;
import com.example.ragagent.a2a.A2aTaskExecution;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rag.multi-agent", name = "runtime", havingValue = "a2a")
public class DefaultA2aMultiAgentRuntime implements MultiAgentRuntime {
    private final A2aRuntime a2aRuntime;

    public DefaultA2aMultiAgentRuntime(A2aRuntime a2aRuntime) {
        this.a2aRuntime = a2aRuntime;
    }

    @Override
    public String name() {
        return "a2a";
    }

    @Override
    public A2aTaskExecution execute(MultiAgentRuntimeRequest request) {
        return a2aRuntime.send(
                request.specialistAgent(),
                request.message(),
                request.request(),
                request.analysis(),
                request.startStep()
        );
    }
}
