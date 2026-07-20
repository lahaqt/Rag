package com.example.ragagent.memory;

import java.util.Locale;

final class MemoryProfileKeys {
    private MemoryProfileKeys() {
    }

    static String classifyPreference(String content) {
        String value = content == null ? "" : content.toLowerCase(Locale.ROOT);
        if (containsAny(value, "language", "chinese", "english", "中文", "英文", "语言")) {
            return "language";
        }
        if (containsAny(value, "concise", "brief", "detailed", "tone", "简洁", "详细", "语气", "风格")) {
            return "response_style";
        }
        if (containsAny(value, "markdown", "json", "table", "format", "表格", "列表", "格式")) {
            return "output_format";
        }
        if (containsAny(value, "java", "python", "spring", "react", "framework", "stack", "技术栈", "框架")) {
            return "technology_preference";
        }
        return "general_preference";
    }

    private static boolean containsAny(String value, String... markers) {
        for (String marker : markers) {
            if (value.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
