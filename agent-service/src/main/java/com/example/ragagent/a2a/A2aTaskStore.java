package com.example.ragagent.a2a;

import java.util.Optional;

public interface A2aTaskStore {
    A2aTask save(A2aTask task);

    Optional<A2aTask> find(String taskId);
}
