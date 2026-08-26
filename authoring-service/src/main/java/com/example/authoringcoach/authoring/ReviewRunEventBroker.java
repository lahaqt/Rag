package com.example.authoringcoach.authoring;

import static com.example.authoringcoach.authoring.AuthoringDtos.ReviewRunEvent;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class ReviewRunEventBroker {
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String runId, List<ReviewRunEvent> replay) {
        SseEmitter emitter = new SseEmitter(0L);
        subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> subscribers.getOrDefault(runId, new CopyOnWriteArrayList<>()).remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(ignored -> remove.run());
        try {
            for (ReviewRunEvent event : replay) send(emitter, event);
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    public void publish(ReviewRunEvent event) {
        for (SseEmitter emitter : subscribers.getOrDefault(event.runId(), new CopyOnWriteArrayList<>())) {
            try {
                send(emitter, event);
            } catch (IOException exception) {
                emitter.complete();
            }
        }
    }

    private void send(SseEmitter emitter, ReviewRunEvent event) throws IOException {
        emitter.send(SseEmitter.event().id(Long.toString(event.id())).name(event.type()).data(event));
    }
}
