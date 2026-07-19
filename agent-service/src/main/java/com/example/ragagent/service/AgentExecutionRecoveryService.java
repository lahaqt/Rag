package com.example.ragagent.service;

import com.example.ragagent.observability.AgentTracePersistenceService;
import com.example.ragagent.observability.RecoverableAgentRun;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** Restarts durable, read-only-safe requests left incomplete by a process restart. */
@Service
public class AgentExecutionRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(AgentExecutionRecoveryService.class);
    private final AgentTracePersistenceService persistenceService;
    private final SpringAiAlibabaAgentRuntime runtime;

    public AgentExecutionRecoveryService(
            AgentTracePersistenceService persistenceService,
            SpringAiAlibabaAgentRuntime runtime
    ) {
        this.persistenceService = persistenceService;
        this.runtime = runtime;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        for (RecoverableAgentRun run : persistenceService.findRecoverableRuns(20)) {
            resumeAsync(run, false);
        }
    }

    /**
     * Explicit recovery permits a caller to retry a run that previously used a
     * side-effecting MCP/function capability. The caller owns that decision.
     */
    public boolean resumeExplicitly(String runId) {
        RecoverableAgentRun run = persistenceService.findRecoverableRun(runId);
        if (run == null || !persistenceService.claimRecovery(runId, true)) {
            return false;
        }
        CompletableFuture.runAsync(() -> execute(run));
        return true;
    }

    private void resumeAsync(RecoverableAgentRun run, boolean manual) {
        if (!persistenceService.claimRecovery(run.runId(), manual)) {
            return;
        }
        CompletableFuture.runAsync(() -> execute(run));
    }

    private void execute(RecoverableAgentRun run) {
        try {
            runtime.resume(run);
        } catch (Exception exception) {
            // The runtime persists the terminal failure; keep startup available.
            log.warn("Recovered agent run failed. runId={} error={}", run.runId(), exception.getMessage());
        }
    }
}
