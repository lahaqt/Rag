package com.example.ragagent.service;

import com.example.ragagent.dto.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class IntentClassifier {
    private static final Set<String> CHITCHAT_EXACT = Set.of(
            "你好", "您好", "谢谢", "感谢", "再见", "拜拜", "哈哈", "嗯嗯", "好的", "收到", "明白了", "ok", "OK"
    );
    private static final List<String> BUSINESS_OPERATION_KEYWORDS = List.of(
            "订单", "订单号", "物流", "快递", "发货", "收货", "运费",
            "余额", "账户余额", "退款", "退货", "退换货", "售后",
            "提交", "申请", "修改地址", "地址", "取消订单", "取消",
            "维修", "报修", "保修", "坏了", "咋整"
    );
    private static final Set<String> AMBIGUOUS_SHORT_QUERIES = Set.of(
            "怎么办", "怎么弄", "帮我看看", "有什么推荐", "出问题了", "有吗", "还有吗"
    );
    private static final List<String> REALTIME_TOOL_KEYWORDS = List.of(
            "今天", "现在", "实时", "最新", "新闻", "天气", "气温", "预报",
            "股价", "汇率", "today", "current", "latest", "news", "weather"
    );
    private static final List<String> MCP_TOOL_KEYWORDS = List.of(
            "mcp", "MCP", "调用工具", "使用工具", "执行工具", "工具调用"
    );
    private static final List<String> SYSTEM_COMMAND_KEYWORDS = List.of(
            "/clear-memory", "/switch-kb", "/set", "清空记忆", "切换知识库", "检索调试", "调试信息", "召回数量", "topK", "TopK", "topk"
    );

    public IntentResult classify(List<ChatMessage> history, String normalizedQuery) {
        List<String> reasons = new ArrayList<>();
        boolean hasHistory = history != null && !history.isEmpty();

        for (String keyword : SYSTEM_COMMAND_KEYWORDS) {
            if (normalizedQuery.contains(keyword)) {
                reasons.add("contains_system_command_keyword:" + keyword);
                return new IntentResult(QueryIntent.SYSTEM_COMMAND, 0.92, reasons);
            }
        }

        if (normalizedQuery.length() <= 8 && CHITCHAT_EXACT.contains(normalizedQuery)) {
            reasons.add("short_message_matches_chitchat_rule");
            return new IntentResult(QueryIntent.CHITCHAT, 0.99, reasons);
        }

        for (String keyword : MCP_TOOL_KEYWORDS) {
            if (normalizedQuery.contains(keyword)) {
                reasons.add("contains_mcp_tool_keyword:" + keyword);
                return new IntentResult(QueryIntent.TOOL, 0.82, reasons);
            }
        }

        for (String keyword : BUSINESS_OPERATION_KEYWORDS) {
            if (normalizedQuery.contains(keyword)) {
                reasons.add("contains_business_operation_keyword:" + keyword);
                return new IntentResult(QueryIntent.KNOWLEDGE, 0.86, reasons);
            }
        }

        for (String keyword : REALTIME_TOOL_KEYWORDS) {
            if (normalizedQuery.toLowerCase().contains(keyword.toLowerCase())) {
                reasons.add("contains_realtime_tool_keyword:" + keyword);
                return new IntentResult(QueryIntent.TOOL, 0.88, reasons);
            }
        }

        if (!hasHistory && normalizedQuery.length() <= 6) {
            reasons.add("short_query_without_history");
            return new IntentResult(QueryIntent.FOLLOW_UP, 0.72, reasons);
        }

        for (String phrase : AMBIGUOUS_SHORT_QUERIES) {
            if (!hasHistory && normalizedQuery.contains(phrase)) {
                reasons.add("ambiguous_query_without_history:" + phrase);
                return new IntentResult(QueryIntent.FOLLOW_UP, 0.74, reasons);
            }
        }

        reasons.add("outside_business_operation_domain");
        return new IntentResult(QueryIntent.FOLLOW_UP, 0.62, reasons);
    }
}
