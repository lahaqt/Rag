package com.example.authoringcoach.authoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded execution policy for one authoring review graph run. */
@Component
public class AuthoringCoachSettings {
    private final int maxReflectionRetries;
    private final int maxExecutionSeconds;
    private final int evidenceLimit;

    public AuthoringCoachSettings(
            @Value("${authoring.review.max-reflection-retries:2}") int maxReflectionRetries,
            @Value("${authoring.review.max-execution-seconds:30}") int maxExecutionSeconds,
            @Value("${authoring.review.evidence-limit:6}") int evidenceLimit
    ) {
        this.maxReflectionRetries = Math.max(0, Math.min(maxReflectionRetries, 4));
        this.maxExecutionSeconds = Math.max(5, Math.min(maxExecutionSeconds, 120));
        this.evidenceLimit = Math.max(1, Math.min(evidenceLimit, 12));
    }

    public int maxReflectionRetries() { return maxReflectionRetries; }
    public int maxExecutionSeconds() { return maxExecutionSeconds; }
    public int evidenceLimit() { return evidenceLimit; }
}
