package com.example.authoringcoach.authoring;

import com.example.authoringcoach.config.RuntimeModelConfigurationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReviewRunWorker {
    private final ReviewRunService runs;
    private final AuthoringCoachService coach;
    private final RuntimeModelConfigurationService models;

    public ReviewRunWorker(ReviewRunService runs, AuthoringCoachService coach,
                           RuntimeModelConfigurationService models) {
        this.runs = runs;
        this.coach = coach;
        this.models = models;
    }

    @Scheduled(fixedDelayString = "${authoring.review.worker-delay-millis:500}")
    public void executeNext() {
        ReviewRunService.ClaimedRun run = runs.claim();
        if (run == null) return;
        try {
            models.withSnapshot(run.modelSnapshot(), () -> coach.execute(
                    run.id(), run.revisionId(), run.userId(), run.checkpoint(), run.trace()));
        } catch (Throwable failure) {
            runs.failOrReschedule(run, failure);
        }
    }
}
