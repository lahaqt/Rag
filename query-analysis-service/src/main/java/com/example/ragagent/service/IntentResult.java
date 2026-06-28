package com.example.ragagent.service;

import java.util.List;

public record IntentResult(
        QueryIntent intent,
        double confidence,
        List<String> reasons
) {
}
