package com.example.ragagent.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.example.ragagent.config.RagProperties;
import com.example.ragagent.a2a.A2aArtifact;
import com.example.ragagent.a2a.A2aMessage;
import com.example.ragagent.a2a.A2aPart;
import com.example.ragagent.a2a.A2aRuntime;
import com.example.ragagent.a2a.A2aTask;
import com.example.ragagent.a2a.A2aTaskExecution;
import com.example.ragagent.a2a.A2aTaskState;
import com.example.ragagent.a2a.A2aTaskStatus;
import com.example.ragagent.dto.AgentTraceStep;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rag.multi-agent", name = "runtime", havingValue = "spring-ai-alibaba", matchIfMissing = true)
public class SpringAiAlibabaMultiAgentRuntime implements MultiAgentRuntime {
    private static final String NODE_PREPARE = "prepare_request";
    private static final String NODE_PLAN = "plan_agents";
    private static final String NODE_SPECIALIST = "run_specialist_agents";
    private static final String NODE_FINISH = "finish_task";
    private static final String KEY_RUN_ID = "runId";
    private static final String KEY_NODE = "node";

    private final A2aRuntime a2aRuntime;
    private final RagProperties properties;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final CompiledGraph graph;
    private final ConcurrentMap<String, MultiAgentRuntimeRequest> requests = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<AgentTraceStep>> traces = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<String>> plans = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, A2aTaskExecution> executions = new ConcurrentHashMap<>();

    public SpringAiAlibabaMultiAgentRuntime(A2aRuntime a2aRuntime) {
        this(
                a2aRuntime,
                new RagProperties(null, null, null, null, null, null, null, null, null),
                Executors.newFixedThreadPool(4),
                true
        );
    }

    @Autowired
    public SpringAiAlibabaMultiAgentRuntime(A2aRuntime a2aRuntime, RagProperties properties) {
        this(
                a2aRuntime,
                properties,
                Executors.newFixedThreadPool(properties == null || properties.multiAgent() == null
                        ? 4
                        : properties.multiAgent().maxConcurrency()),
                true
        );
    }

    public SpringAiAlibabaMultiAgentRuntime(
            A2aRuntime a2aRuntime,
            RagProperties properties,
            ExecutorService executor,
            boolean ownsExecutor
    ) {
        this.a2aRuntime = a2aRuntime;
        this.properties = properties == null
                ? new RagProperties(null, null, null, null, null, null, null, null, null)
                : properties;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.graph = compileGraph();
    }

    @Override
    public String name() {
        return "spring-ai-alibaba";
    }

    @Override
    public A2aTaskExecution execute(MultiAgentRuntimeRequest request) {
        String runId = "multi-agent-" + UUID.randomUUID();
        requests.put(runId, request);
        try {
            graph.invoke(Map.of(KEY_RUN_ID, runId))
                    .orElseThrow(() -> new IllegalStateException("Spring AI Alibaba graph returned empty state."));
            A2aTaskExecution execution = executions.get(runId);
            if (execution == null) {
                throw new IllegalStateException("Spring AI Alibaba graph did not produce an A2A task execution.");
            }
            return execution;
        } finally {
            requests.remove(runId);
            traces.remove(runId);
            plans.remove(runId);
            executions.remove(runId);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }

    private CompiledGraph compileGraph() {
        try {
            StateGraph stateGraph = new StateGraph(
                    "rag-multi-agent",
                    KeyStrategy.builder().defaultStrategy(KeyStrategy.REPLACE).build()
            );
            stateGraph.addNode(NODE_PREPARE, AsyncNodeAction.node_async(this::prepareRequest));
            stateGraph.addNode(NODE_PLAN, AsyncNodeAction.node_async(this::planAgents));
            stateGraph.addNode(NODE_SPECIALIST, AsyncNodeAction.node_async(this::runSpecialistAgent));
            stateGraph.addNode(NODE_FINISH, AsyncNodeAction.node_async(this::finishTask));
            stateGraph.addEdge(StateGraph.START, NODE_PREPARE);
            stateGraph.addEdge(NODE_PREPARE, NODE_PLAN);
            stateGraph.addEdge(NODE_PLAN, NODE_SPECIALIST);
            stateGraph.addEdge(NODE_SPECIALIST, NODE_FINISH);
            stateGraph.addEdge(NODE_FINISH, StateGraph.END);
            return stateGraph.compile();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to compile Spring AI Alibaba multi-agent graph.", exception);
        }
    }

    private Map<String, Object> prepareRequest(OverAllState state) {
        MultiAgentRuntimeRequest request = request(state);
        List<AgentTraceStep> trace = new ArrayList<>();
        trace.add(new AgentTraceStep(
                request.startStep(),
                "spring_ai_alibaba_graph",
                request.analysis().route(),
                request.specialistAgent().name(),
                "graph_started",
                "graph=rag-multi-agent; node=" + NODE_PREPARE
                        + "; selectedAgent=" + request.supervisorDecision().agentName()
        ));
        traces.put(runId(state), trace);
        return Map.of(KEY_NODE, NODE_PREPARE);
    }

    private Map<String, Object> planAgents(OverAllState state) {
        MultiAgentRuntimeRequest request = request(state);
        List<String> plan = plan(request);
        plans.put(runId(state), plan);

        List<AgentTraceStep> trace = trace(state);
        trace.add(new AgentTraceStep(
                request.startStep() + trace.size(),
                "spring_ai_alibaba_graph",
                request.analysis().route(),
                String.join(",", plan),
                "graph_planned",
                "graph=rag-multi-agent; node=" + NODE_PLAN
                        + "; plan=" + String.join(" -> ", plan)
        ));
        traces.put(runId(state), trace);
        return Map.of(KEY_NODE, NODE_PLAN);
    }

    private Map<String, Object> runSpecialistAgent(OverAllState state) {
        MultiAgentRuntimeRequest request = request(state);
        List<AgentTraceStep> trace = trace(state);
        List<String> plan = plans.getOrDefault(runId(state), List.of(request.specialistAgent().name()));
        List<CompletableFuture<A2aTaskExecution>> futures = new ArrayList<>();
        int step = request.startStep() + trace.size();
        for (int i = 0; i < plan.size(); i++) {
            String agentName = plan.get(i);
            SpecialistAgent specialistAgent = request.specialistAgents().getOrDefault(agentName, request.specialistAgent());
            int agentStartStep = step + 1 + (i * 10);
            futures.add(CompletableFuture
                    .supplyAsync(() -> sendWithIsolation(request, specialistAgent, agentStartStep), executor)
                    .orTimeout(timeoutSeconds(), TimeUnit.SECONDS)
                    .exceptionally(exception -> {
                        Throwable cause = unwrap(exception);
                        if (!failureIsolationEnabled()) {
                            throw new CompletionException(cause);
                        }
                        return failedExecution(request, specialistAgent, agentStartStep, cause);
                    }));
        }
        List<A2aTaskExecution> stepExecutions = futures.stream()
                .map(CompletableFuture::join)
                .toList();
        stepExecutions.forEach(execution -> trace.addAll(execution.trace()));
        A2aTaskExecution execution = combine(request, stepExecutions, trace);
        traces.put(runId(state), trace);
        executions.put(runId(state), execution);
        return Map.of(KEY_NODE, NODE_SPECIALIST);
    }

    private A2aTaskExecution sendWithIsolation(
            MultiAgentRuntimeRequest request,
            SpecialistAgent specialistAgent,
            int startStep
    ) {
        try {
            return a2aRuntime.send(
                    specialistAgent,
                    request.message(),
                    request.request(),
                    request.analysis(),
                    startStep
            );
        } catch (Exception exception) {
            if (!failureIsolationEnabled()) {
                throw new CompletionException(exception);
            }
            return failedExecution(request, specialistAgent, startStep, exception);
        }
    }

    private A2aTaskExecution failedExecution(
            MultiAgentRuntimeRequest request,
            SpecialistAgent specialistAgent,
            int startStep,
            Throwable throwable
    ) {
        String message = throwable == null || throwable.getMessage() == null
                ? "specialist agent failed"
                : throwable.getMessage();
        AgentToolResult toolResult = AgentToolResult.failure(
                "multi_agent",
                request.request().query(),
                specialistAgent.name() + "_failed: " + message,
                "specialist_agent_failed"
        );
        A2aMessage failedMessage = new A2aMessage(
                "agent",
                "msg-" + UUID.randomUUID(),
                request.message().contextId(),
                "task-" + specialistAgent.name() + "-" + UUID.randomUUID(),
                List.of(),
                List.of(A2aPart.text(toolResult.observation()))
        );
        A2aTask task = new A2aTask(
                failedMessage.taskId(),
                failedMessage.contextId(),
                new A2aTaskStatus(A2aTaskState.FAILED, failedMessage, Instant.now()),
                List.of(request.message(), failedMessage),
                List.of(new A2aArtifact(
                        "artifact-" + specialistAgent.name() + "-" + UUID.randomUUID(),
                        specialistAgent.name() + "-failure",
                        "Failure isolated by the multi-agent runtime.",
                        List.of(
                                A2aPart.text(toolResult.observation()),
                                A2aPart.data(Map.of(
                                        "agentName", specialistAgent.name(),
                                        "success", false,
                                        "finishReason", toolResult.finishReason()
                                ))
                        )
                ))
        );
        SpecialistAgentResult result = new SpecialistAgentResult(
                "multi_agent",
                new ToolDecision(true, "multi_agent", request.request().query(), "specialist_agent_failed:" + specialistAgent.name()),
                toolResult,
                task,
                List.of(AgentTraceStep.failed(
                        startStep,
                        "agent",
                        request.analysis().route(),
                        specialistAgent.name(),
                        "agent_observation",
                        message,
                        0,
                        throwable instanceof Exception exception ? exception : new RuntimeException(throwable)
                ))
        );
        return new A2aTaskExecution(task, result, result.trace());
    }

    private int timeoutSeconds() {
        return properties.multiAgent() == null ? 12 : properties.multiAgent().timeoutSeconds();
    }

    private boolean failureIsolationEnabled() {
        return properties.multiAgent() == null || properties.multiAgent().failureIsolationEnabled();
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException completionException && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return throwable;
    }

    private Map<String, Object> finishTask(OverAllState state) {
        String runId = runId(state);
        MultiAgentRuntimeRequest request = request(state);
        A2aTaskExecution execution = executions.get(runId);
        if (execution == null) {
            throw new IllegalStateException("Specialist agent execution is missing.");
        }

        List<AgentTraceStep> trace = trace(state);
        trace.add(new AgentTraceStep(
                request.startStep() + execution.trace().size() + 2,
                "spring_ai_alibaba_graph",
                request.analysis().route(),
                request.specialistAgent().name(),
                "graph_completed",
                "graph=rag-multi-agent; node=" + NODE_FINISH
                        + "; taskId=" + execution.task().id()
                        + "; delegatedAgent=" + execution.agentResult().agentName()
        ));
        A2aTaskExecution completedExecution = new A2aTaskExecution(execution.task(), execution.agentResult(), trace);
        traces.put(runId, trace);
        executions.put(runId, completedExecution);
        return Map.of(KEY_NODE, NODE_FINISH);
    }

    private List<String> plan(MultiAgentRuntimeRequest request) {
        List<String> plan = new ArrayList<>();
        if (isFollowUp(request)) {
            addIfAvailable(plan, request, "follow_up");
            return plan.isEmpty() ? List.of(request.specialistAgent().name()) : plan;
        }

        List<String> capabilities = request.analysis().safeRequiredCapabilities();
        if (capabilities.contains("rag_retrieval") || isKnowledgeRoute(request)) {
            addIfAvailable(plan, request, "knowledge");
        }
        if (capabilities.contains("web_search") || isRealtime(request)) {
            addIfAvailable(plan, request, "web_search");
        }
        if (capabilities.contains("mcp_tool")) {
            addIfAvailable(plan, request, "mcp_tool");
        }
        addIfAvailable(plan, request, request.supervisorDecision().agentName());
        if (plan.isEmpty()) {
            addIfAvailable(plan, request, request.specialistAgent().name());
        }
        return plan;
    }

    private A2aTaskExecution combine(
            MultiAgentRuntimeRequest request,
            List<A2aTaskExecution> executions,
            List<AgentTraceStep> trace
    ) {
        if (executions.isEmpty()) {
            throw new IllegalStateException("Spring AI Alibaba graph did not execute any specialist agent.");
        }
        if (executions.size() == 1) {
            return executions.get(0);
        }

        List<SpecialistAgentResult> results = executions.stream()
                .map(A2aTaskExecution::agentResult)
                .toList();
        List<String> agentNames = results.stream().map(SpecialistAgentResult::agentName).toList();
        List<com.example.ragagent.dto.RetrievalHit> retrievalHits = results.stream()
                .flatMap(result -> result.retrievalHits().stream())
                .toList();
        List<com.example.ragagent.dto.WebSearchResult> webSearchResults = results.stream()
                .flatMap(result -> result.webSearchResults().stream())
                .toList();
        boolean success = results.stream()
                .map(SpecialistAgentResult::toolResult)
                .allMatch(result -> result == null || result.success());
        String observation = results.stream()
                .map(result -> result.agentName() + "=" + (result.toolResult() == null ? "no_tool" : result.toolResult().observation()))
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
        AgentToolResult combinedToolResult = new AgentToolResult(
                "multi_agent",
                request.request().query(),
                success,
                observation,
                (success ? "multi_agent_completed:" : "multi_agent_partial_failure:") + String.join(",", agentNames),
                retrievalHits,
                webSearchResults
        );
        SpecialistAgentResult combinedResult = new SpecialistAgentResult(
                "multi_agent",
                new ToolDecision(true, "multi_agent", request.request().query(), "spring_ai_alibaba_multi_agent_plan"),
                combinedToolResult,
                executions.get(executions.size() - 1).task(),
                trace
        );
        return new A2aTaskExecution(executions.get(executions.size() - 1).task(), combinedResult, trace);
    }

    private void addIfAvailable(List<String> plan, MultiAgentRuntimeRequest request, String agentName) {
        if (agentName == null || agentName.isBlank() || plan.contains(agentName)) {
            return;
        }
        if (request.specialistAgents().containsKey(agentName)) {
            plan.add(agentName);
        }
    }

    private boolean isFollowUp(MultiAgentRuntimeRequest request) {
        return "follow_up".equals(request.analysis().intent()) || "ask_follow_up".equals(request.analysis().route());
    }

    private boolean isKnowledgeRoute(MultiAgentRuntimeRequest request) {
        return "knowledge".equals(request.analysis().intent()) || "knowledge_retrieval".equals(request.analysis().route());
    }

    private boolean isRealtime(MultiAgentRuntimeRequest request) {
        String query = request.request().query() == null ? "" : request.request().query().toLowerCase();
        return query.contains("today")
                || query.contains("latest")
                || query.contains("current")
                || query.contains("now")
                || query.contains("今天")
                || query.contains("最新")
                || query.contains("当前");
    }

    private MultiAgentRuntimeRequest request(OverAllState state) {
        MultiAgentRuntimeRequest request = requests.get(runId(state));
        if (request == null) {
            throw new IllegalStateException("Spring AI Alibaba graph request is missing.");
        }
        return request;
    }

    private List<AgentTraceStep> trace(OverAllState state) {
        return new ArrayList<>(traces.getOrDefault(runId(state), List.of()));
    }

    private String runId(OverAllState state) {
        return state.value(KEY_RUN_ID, String.class)
                .orElseThrow(() -> new IllegalStateException("Spring AI Alibaba graph run id is missing."));
    }
}
