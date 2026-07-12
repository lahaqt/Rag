package com.example.ragagent.service;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import java.util.Map;

/**
 * Builds graph topology independently of request behavior.
 *
 * <p>The factory contains names, edges and recursion limits only. Node actions
 * are supplied by {@link SpringAiAlibabaAgentRuntime}, which keeps routing,
 * retrieval, prompt composition and persistence testable without duplicating
 * two graph implementations.</p>
 */
final class AgentGraphFactory {
    private static final String NODE_PREPARE = "prepare_context";
    private static final String NODE_ANALYZE = "query_analysis";
    private static final String NODE_ROUTE = "route_capabilities";
    private static final String NODE_EXECUTE = "execute_capability";
    private static final String NODE_REPLAN = "replan_capability";
    private static final String NODE_KNOWLEDGE = "knowledge_agent";
    private static final String NODE_WEB = "web_search_agent";
    private static final String NODE_MCP = "mcp_tool_agent";
    private static final String NODE_FANOUT_KNOWLEDGE_WEB = "fanout_knowledge_web";
    private static final String NODE_FANOUT_KNOWLEDGE_MCP = "fanout_knowledge_mcp";
    private static final String NODE_FANOUT_WEB_MCP = "fanout_web_mcp";
    private static final String NODE_FANOUT_ALL = "fanout_all_capabilities";
    private static final String NODE_GENERATE = "generate_answer";
    private static final String NODE_REFLECT = "reflect_answer";
    private static final String NODE_FINALIZE = "finalize_response";
    private static final String EDGE_RETRY = "retry";
    private static final String EDGE_FINISH = "finish";
    private static final String EDGE_EXECUTE_NEXT = "execute_next";
    private static final String EDGE_GENERATE = "generate";
    private static final String EDGE_REPLAN_CAPABILITY = "replan_capability";
    private static final String EDGE_DIRECT = "direct";
    private static final String EDGE_KNOWLEDGE = "knowledge";
    private static final String EDGE_WEB = "web";
    private static final String EDGE_MCP = "mcp";
    private static final String EDGE_KNOWLEDGE_WEB = "knowledge_web";
    private static final String EDGE_KNOWLEDGE_MCP = "knowledge_mcp";
    private static final String EDGE_WEB_MCP = "web_mcp";
    private static final String EDGE_ALL_CAPABILITIES = "all_capabilities";

    private AgentGraphFactory() {
    }

    static Graphs compile(int maxToolIterations, int maxReflectionRetries, Nodes nodes, Edges edges) {
        CompileConfig config = CompileConfig.builder()
                .recursionLimit(Math.max(24, 10 + maxToolIterations * 3 + maxReflectionRetries * 4))
                .releaseThread(false)
                .build();
        return new Graphs(compileOrdinary(nodes, edges, config), compileMultiAgent(nodes, edges, config));
    }

    private static CompiledGraph compileOrdinary(Nodes nodes, Edges edges, CompileConfig config) {
        try {
            StateGraph graph = graph("rag-agent");
            graph.addNode(NODE_PREPARE, AsyncNodeAction.node_async(nodes.prepare()));
            graph.addNode(NODE_ANALYZE, AsyncNodeAction.node_async(nodes.analyze()));
            graph.addNode(NODE_ROUTE, AsyncNodeAction.node_async(nodes.route()));
            graph.addNode(NODE_EXECUTE, AsyncNodeAction.node_async(nodes.executeOrdinary()));
            graph.addNode(NODE_REPLAN, AsyncNodeAction.node_async(nodes.replanOrdinary()));
            graph.addNode(NODE_GENERATE, AsyncNodeAction.node_async(nodes.generate()));
            graph.addNode(NODE_REFLECT, AsyncNodeAction.node_async(nodes.reflect()));
            graph.addNode(NODE_FINALIZE, AsyncNodeAction.node_async(nodes.finalizeResponse()));
            graph.addEdge(StateGraph.START, NODE_PREPARE);
            graph.addEdge(NODE_PREPARE, NODE_ANALYZE);
            graph.addEdge(NODE_ANALYZE, NODE_ROUTE);
            graph.addEdge(NODE_ROUTE, NODE_EXECUTE);
            graph.addEdge(NODE_EXECUTE, NODE_REPLAN);
            graph.addConditionalEdges(
                    NODE_REPLAN,
                    AsyncEdgeAction.edge_async(edges.ordinaryReplan()),
                    Map.of(EDGE_EXECUTE_NEXT, NODE_EXECUTE, EDGE_GENERATE, NODE_GENERATE)
            );
            graph.addEdge(NODE_GENERATE, NODE_REFLECT);
            graph.addConditionalEdges(
                    NODE_REFLECT,
                    AsyncEdgeAction.edge_async(edges.ordinaryReflection()),
                    Map.of(EDGE_RETRY, NODE_GENERATE, EDGE_REPLAN_CAPABILITY, NODE_EXECUTE, EDGE_FINISH, NODE_FINALIZE)
            );
            graph.addEdge(NODE_FINALIZE, StateGraph.END);
            return graph.compile(config);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to compile ordinary Spring AI Alibaba agent graph.", exception);
        }
    }

    private static CompiledGraph compileMultiAgent(Nodes nodes, Edges edges, CompileConfig config) {
        try {
            StateGraph graph = graph("rag-multi-agent");
            graph.addNode(NODE_PREPARE, AsyncNodeAction.node_async(nodes.prepare()));
            graph.addNode(NODE_ANALYZE, AsyncNodeAction.node_async(nodes.analyze()));
            graph.addNode(NODE_ROUTE, AsyncNodeAction.node_async(nodes.route()));
            graph.addNode(NODE_KNOWLEDGE, AsyncNodeAction.node_async(nodes.executeKnowledge()));
            graph.addNode(NODE_WEB, AsyncNodeAction.node_async(nodes.executeWebSearch()));
            graph.addNode(NODE_MCP, AsyncNodeAction.node_async(nodes.executeMcp()));
            graph.addNode(NODE_FANOUT_KNOWLEDGE_WEB, AsyncNodeAction.node_async(nodes.multiAgentFanOut()));
            graph.addNode(NODE_FANOUT_KNOWLEDGE_MCP, AsyncNodeAction.node_async(nodes.multiAgentFanOut()));
            graph.addNode(NODE_FANOUT_WEB_MCP, AsyncNodeAction.node_async(nodes.multiAgentFanOut()));
            graph.addNode(NODE_FANOUT_ALL, AsyncNodeAction.node_async(nodes.multiAgentFanOut()));
            graph.addNode(NODE_GENERATE, AsyncNodeAction.node_async(nodes.generate()));
            graph.addNode(NODE_REFLECT, AsyncNodeAction.node_async(nodes.reflect()));
            graph.addNode(NODE_FINALIZE, AsyncNodeAction.node_async(nodes.finalizeResponse()));
            graph.addEdge(StateGraph.START, NODE_PREPARE);
            graph.addEdge(NODE_PREPARE, NODE_ANALYZE);
            graph.addEdge(NODE_ANALYZE, NODE_ROUTE);
            graph.addConditionalEdges(
                    NODE_ROUTE,
                    AsyncEdgeAction.edge_async(edges.multiAgentDispatch()),
                    Map.of(
                            EDGE_DIRECT, NODE_GENERATE,
                            EDGE_KNOWLEDGE, NODE_KNOWLEDGE,
                            EDGE_WEB, NODE_WEB,
                            EDGE_MCP, NODE_MCP,
                            EDGE_KNOWLEDGE_WEB, NODE_FANOUT_KNOWLEDGE_WEB,
                            EDGE_KNOWLEDGE_MCP, NODE_FANOUT_KNOWLEDGE_MCP,
                            EDGE_WEB_MCP, NODE_FANOUT_WEB_MCP,
                            EDGE_ALL_CAPABILITIES, NODE_FANOUT_ALL
                    )
            );
            graph.addEdge(NODE_FANOUT_KNOWLEDGE_WEB, NODE_KNOWLEDGE);
            graph.addEdge(NODE_FANOUT_KNOWLEDGE_WEB, NODE_WEB);
            graph.addEdge(NODE_FANOUT_KNOWLEDGE_MCP, NODE_KNOWLEDGE);
            graph.addEdge(NODE_FANOUT_KNOWLEDGE_MCP, NODE_MCP);
            graph.addEdge(NODE_FANOUT_WEB_MCP, NODE_WEB);
            graph.addEdge(NODE_FANOUT_WEB_MCP, NODE_MCP);
            graph.addEdge(NODE_FANOUT_ALL, NODE_KNOWLEDGE);
            graph.addEdge(NODE_FANOUT_ALL, NODE_WEB);
            graph.addEdge(NODE_FANOUT_ALL, NODE_MCP);
            graph.addEdge(NODE_KNOWLEDGE, NODE_GENERATE);
            graph.addEdge(NODE_WEB, NODE_GENERATE);
            graph.addEdge(NODE_MCP, NODE_GENERATE);
            graph.addEdge(NODE_GENERATE, NODE_REFLECT);
            graph.addConditionalEdges(
                    NODE_REFLECT,
                    AsyncEdgeAction.edge_async(edges.multiAgentReflection()),
                    Map.of(EDGE_RETRY, NODE_GENERATE, EDGE_FINISH, NODE_FINALIZE)
            );
            graph.addEdge(NODE_FINALIZE, StateGraph.END);
            return graph.compile(config);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to compile multi-agent Spring AI Alibaba agent graph.", exception);
        }
    }

    private static StateGraph graph(String name) {
        return new StateGraph(name, KeyStrategy.builder().defaultStrategy(KeyStrategy.REPLACE).build());
    }

    record Graphs(CompiledGraph ordinary, CompiledGraph multiAgent) {
    }

    record Nodes(
            NodeAction prepare,
            NodeAction analyze,
            NodeAction route,
            NodeAction executeOrdinary,
            NodeAction replanOrdinary,
            NodeAction executeKnowledge,
            NodeAction executeWebSearch,
            NodeAction executeMcp,
            NodeAction multiAgentFanOut,
            NodeAction generate,
            NodeAction reflect,
            NodeAction finalizeResponse
    ) {
    }

    record Edges(
            EdgeAction ordinaryReplan,
            EdgeAction ordinaryReflection,
            EdgeAction multiAgentDispatch,
            EdgeAction multiAgentReflection
    ) {
    }
}
