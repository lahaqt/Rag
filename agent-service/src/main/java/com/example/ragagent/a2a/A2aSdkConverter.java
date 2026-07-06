package com.example.ragagent.a2a;

import io.a2a.spec.Artifact;
import io.a2a.spec.DataPart;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TextPart;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

final class A2aSdkConverter {
    private A2aSdkConverter() {
    }

    static Task toSdkTask(A2aTask task) {
        return toSdkTask(task, task.id(), task.contextId());
    }

    static Task toSdkTask(A2aTask task, String taskId, String contextId) {
        return new Task.Builder()
                .id(taskId)
                .contextId(contextId)
                .status(toSdkStatus(task.status(), taskId, contextId))
                .history(task.history().stream().map(message -> toSdkMessage(message, taskId, contextId)).toList())
                .artifacts(task.artifacts().stream().map(A2aSdkConverter::toSdkArtifact).toList())
                .metadata(Map.of())
                .build();
    }

    static String text(Message message) {
        if (message == null || message.getParts() == null) {
            return "";
        }
        return message.getParts().stream()
                .filter(TextPart.class::isInstance)
                .map(TextPart.class::cast)
                .map(TextPart::getText)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    static String stringMetadata(Message message, String key) {
        if (message == null) {
            return null;
        }
        Object metadataValue = message.getMetadata() == null ? null : message.getMetadata().get(key);
        if (metadataValue instanceof String string && !string.isBlank()) {
            return string;
        }
        if (message.getParts() == null) {
            return null;
        }
        return message.getParts().stream()
                .filter(DataPart.class::isInstance)
                .map(DataPart.class::cast)
                .map(DataPart::getData)
                .map(data -> data.get(key))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static TaskStatus toSdkStatus(A2aTaskStatus status, String taskId, String contextId) {
        LocalDateTime timestamp = LocalDateTime.ofInstant(status.timestamp(), ZoneOffset.UTC);
        return new TaskStatus(toSdkState(status.state()), toSdkMessage(status.message(), taskId, contextId), timestamp);
    }

    private static TaskState toSdkState(A2aTaskState state) {
        return switch (state) {
            case SUBMITTED -> TaskState.SUBMITTED;
            case WORKING -> TaskState.WORKING;
            case INPUT_REQUIRED -> TaskState.INPUT_REQUIRED;
            case COMPLETED -> TaskState.COMPLETED;
            case CANCELED -> TaskState.CANCELED;
            case FAILED -> TaskState.FAILED;
        };
    }

    private static Message toSdkMessage(A2aMessage message) {
        return toSdkMessage(message, message.taskId(), message.contextId());
    }

    private static Message toSdkMessage(A2aMessage message, String taskId, String contextId) {
        return new Message.Builder()
                .role("agent".equalsIgnoreCase(message.role()) ? Message.Role.AGENT : Message.Role.USER)
                .messageId(message.messageId())
                .contextId(contextId)
                .taskId(taskId)
                .referenceTaskIds(message.referenceTaskIds())
                .parts(message.parts().stream().map(A2aSdkConverter::toSdkPart).toList())
                .metadata(Map.of())
                .build();
    }

    private static Artifact toSdkArtifact(A2aArtifact artifact) {
        return new Artifact.Builder()
                .artifactId(artifact.artifactId())
                .name(artifact.name())
                .description(artifact.description())
                .parts(artifact.parts().stream().map(A2aSdkConverter::toSdkPart).toList())
                .metadata(Map.of())
                .build();
    }

    private static Part<?> toSdkPart(A2aPart part) {
        if ("data".equals(part.kind())) {
            return new DataPart(part.data());
        }
        return new TextPart(part.text());
    }
}
