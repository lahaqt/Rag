package com.example.ragagent.service;

import com.example.ragagent.dto.WebSearchResult;
import java.util.List;

public interface WebSearchTool {
    List<WebSearchResult> search(String query);
}
