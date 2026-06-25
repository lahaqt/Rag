package com.example.ragagent.service;

import com.example.ragagent.dto.ChatMessage;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ConversationHistoryFormatter {
    public String format(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "（无历史对话）";
        }

        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : history) {
            String role = "assistant".equalsIgnoreCase(message.role()) ? "助手" : "用户";
            builder.append(role).append("：").append(message.content()).append('\n');
        }
        return builder.toString().trim();
    }
}
