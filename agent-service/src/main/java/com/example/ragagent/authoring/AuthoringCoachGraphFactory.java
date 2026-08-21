package com.example.ragagent.authoring;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import java.util.Map;

/** Defines the bounded, evidence-first topology of one authoring review. */
final class AuthoringCoachGraphFactory {
    static final String EDGE_REVIEW = "review";
    static final String EDGE_INSUFFICIENT = "insufficient";
    static final String EDGE_RETRY = "retry";
    static final String EDGE_AGGREGATE = "aggregate";

    private AuthoringCoachGraphFactory() {
    }

    static CompiledGraph compile(int maxReflectionRetries, Nodes nodes, Edges edges) {
        try {
            StateGraph graph = new StateGraph(
                    "authoring-coach",
                    KeyStrategy.builder().defaultStrategy(KeyStrategy.REPLACE).build()
            );
            graph.addNode("understand_task", AsyncNodeAction.node_async(nodes.understandTask()));
            graph.addNode("retrieve_evidence", AsyncNodeAction.node_async(nodes.retrieveEvidence()));
            graph.addNode("assess_evidence", AsyncNodeAction.node_async(nodes.assessEvidence()));
            graph.addNode("retrieve_supplements", AsyncNodeAction.node_async(nodes.retrieveSupplements()));
            graph.addNode("rubric_review", AsyncNodeAction.node_async(nodes.rubricReview()));
            graph.addNode("reflect_review", AsyncNodeAction.node_async(nodes.reflectReview()));
            graph.addNode("aggregate_result", AsyncNodeAction.node_async(nodes.aggregateResult()));
            graph.addEdge(StateGraph.START, "understand_task");
            graph.addEdge("understand_task", "retrieve_evidence");
            graph.addEdge("retrieve_evidence", "assess_evidence");
            graph.addConditionalEdges(
                    "assess_evidence",
                    AsyncEdgeAction.edge_async(edges.evidenceDecision()),
                    Map.of(EDGE_REVIEW, "retrieve_supplements", EDGE_INSUFFICIENT, "aggregate_result")
            );
            graph.addEdge("retrieve_supplements", "rubric_review");
            graph.addEdge("rubric_review", "reflect_review");
            graph.addConditionalEdges(
                    "reflect_review",
                    AsyncEdgeAction.edge_async(edges.reflectionDecision()),
                    Map.of(EDGE_RETRY, "rubric_review", EDGE_AGGREGATE, "aggregate_result")
            );
            graph.addEdge("aggregate_result", StateGraph.END);
            return graph.compile(CompileConfig.builder()
                    .recursionLimit(Math.max(16, 10 + maxReflectionRetries * 2))
                    .releaseThread(false)
                    .build());
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to compile the Authoring Coach graph", exception);
        }
    }

    record Nodes(
            NodeAction understandTask,
            NodeAction retrieveEvidence,
            NodeAction assessEvidence,
            NodeAction retrieveSupplements,
            NodeAction rubricReview,
            NodeAction reflectReview,
            NodeAction aggregateResult
    ) {
    }

    record Edges(EdgeAction evidenceDecision, EdgeAction reflectionDecision) {
    }
}
