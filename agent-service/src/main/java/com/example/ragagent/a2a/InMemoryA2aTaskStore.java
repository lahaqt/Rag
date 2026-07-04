package com.example.ragagent.a2a;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryA2aTaskStore implements A2aTaskStore {
    private final ConcurrentMap<String, A2aTask> tasks = new ConcurrentHashMap<>();

    @Override
    public A2aTask save(A2aTask task) {
        if (task == null || task.id() == null || task.id().isBlank()) {
            return task;
        }
        tasks.put(task.id(), task);
        return task;
    }

    @Override
    public Optional<A2aTask> find(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tasks.get(taskId));
    }
}
