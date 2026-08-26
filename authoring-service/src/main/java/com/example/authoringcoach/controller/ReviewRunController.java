package com.example.authoringcoach.controller;

import static com.example.authoringcoach.authoring.AuthoringDtos.ReviewRun;

import com.example.authoringcoach.authoring.ReviewRunEventBroker;
import com.example.authoringcoach.authoring.ReviewRunService;
import com.example.authoringcoach.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class ReviewRunController {
    private final ReviewRunService runs;
    private final ReviewRunEventBroker events;

    public ReviewRunController(ReviewRunService runs, ReviewRunEventBroker events) {
        this.runs = runs;
        this.events = events;
    }

    @PostMapping("/revisions/{revisionId}/review-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReviewRun create(HttpServletRequest request, @PathVariable String revisionId,
                            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return runs.enqueue(revisionId, RequestIdentity.requiredUserId(request), idempotencyKey);
    }

    @GetMapping("/review-runs/{runId}")
    public ReviewRun get(HttpServletRequest request, @PathVariable String runId) {
        return runs.get(runId, RequestIdentity.requiredUserId(request));
    }

    @GetMapping(path = "/review-runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(HttpServletRequest request, @PathVariable String runId,
                             @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long lastEventId) {
        String userId = RequestIdentity.requiredUserId(request);
        return events.subscribe(runId, runs.events(runId, userId, lastEventId));
    }

    @PostMapping("/review-runs/{runId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReviewRun retry(HttpServletRequest request, @PathVariable String runId) {
        return runs.retry(runId, RequestIdentity.requiredUserId(request));
    }
}
