package com.example.ragagent.service;

import java.util.List;

public record AgentPlan(List<String> steps) {
    public AgentPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
