package com.example.ragagent.service;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ToolRouter {
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<String> WEB_SEARCH_KEYWORDS = List.of(
            "今天", "现在", "实时", "最新", "新闻", "天气", "气温", "预报",
            "股价", "汇率", "价格", "搜索", "查一下", "联网", "刚刚", "当前",
            "today", "current", "latest", "news", "weather", "search"
    );

    public ToolDecision decide(ChatRequest request, QueryAnalysisResponse analysis) {
        String query = request.query() == null ? "" : request.query().trim();
        String normalized = query.toLowerCase();
        for (String keyword : WEB_SEARCH_KEYWORDS) {
            if (normalized.contains(keyword.toLowerCase())) {
                return ToolDecision.webSearch(enrichQuery(query), "contains_realtime_keyword:" + keyword);
            }
        }

        if ("tool".equals(analysis.intent()) || "tool_invocation".equals(analysis.route())) {
            return ToolDecision.webSearch(enrichQuery(query), "query_analysis_routed_to_tool");
        }

        return ToolDecision.none();
    }

    private String enrichQuery(String query) {
        LocalDate today = LocalDate.now(DEFAULT_ZONE);
        if (query.contains("天气") || query.contains("气温") || query.contains("预报")
                || query.toLowerCase().contains("weather")) {
            String weatherQuery = query;
            for (String filler : List.of("今天", "现在", "实时", "当前", "怎么样", "如何", "咋样", "呢", "吗", "？", "?")) {
                weatherQuery = weatherQuery.replace(filler, "");
            }
            weatherQuery = weatherQuery.replaceAll("\\s+", "");
            if (!weatherQuery.contains("天气")) {
                weatherQuery = weatherQuery + "天气";
            }
            if (!weatherQuery.contains("预报")) {
                weatherQuery = weatherQuery + "预报";
            }
            return weatherQuery;
        }
        if (query.contains("今天") || query.toLowerCase().contains("today")) {
            return query + " " + today;
        }
        return query;
    }
}
