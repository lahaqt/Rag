package com.example.ragagent.service;

import com.example.ragagent.a2a.A2aTaskExecution;

public interface MultiAgentRuntime {
    String name();

    A2aTaskExecution execute(MultiAgentRuntimeRequest request);
}
