package com.example.ragagent.client;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.WebSearchResult;
import com.example.ragagent.service.WebSearchTool;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;

@Component
public class DuckDuckGoWebSearchTool implements WebSearchTool {
    private static final Pattern RESULT_PATTERN = Pattern.compile(
            "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern BING_MARKER_PATTERN = Pattern.compile(
            "class=\"[^\"]*b_algo[^\"]*\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BING_LINK_PATTERN = Pattern.compile(
            "<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern BING_SNIPPET_PATTERN = Pattern.compile(
            "<p[^>]*>(.*?)</p>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern RSS_ITEM_PATTERN = Pattern.compile(
            "<item>(.*?)</item>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern RSS_TITLE_PATTERN = Pattern.compile(
            "<title>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern RSS_LINK_PATTERN = Pattern.compile(
            "<link>(.*?)</link>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern RSS_DESCRIPTION_PATTERN = Pattern.compile(
            "<description>(.*?)</description>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern SNIPPET_PATTERN = Pattern.compile(
            "<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>|<td[^>]*class=\"result-snippet\"[^>]*>(.*?)</td>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    private final RestClient restClient;
    private final RagProperties.WebSearch properties;

    public DuckDuckGoWebSearchTool(RagProperties properties) {
        this.properties = properties.tools().webSearch();
        this.restClient = RestClient.builder()
                .baseUrl(this.properties.baseUrl())
                .defaultHeader("User-Agent", "Mozilla/5.0 RAG-Agent/1.0")
                .defaultHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
                .build();
    }

    @Override
    public List<WebSearchResult> search(String query) {
        if ("bing".equalsIgnoreCase(properties.provider())) {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
            String rss = restClient.get()
                    .uri(URI.create(properties.baseUrl() + "/search?q=" + encodedQuery + "&format=rss"))
                    .accept(MediaType.APPLICATION_XML, MediaType.TEXT_XML)
                    .retrieve()
                    .body(String.class);
            List<WebSearchResult> rssResults = parseBingRssResults(rss == null ? "" : rss, properties.maxResults());
            if (!rssResults.isEmpty()) {
                return rssResults;
            }

            String html = restClient.get()
                    .uri(URI.create(properties.baseUrl() + "/search?q=" + encodedQuery))
                    .accept(MediaType.TEXT_HTML)
                    .retrieve()
                    .body(String.class);
            return parseBingHtmlResults(html == null ? "" : html, properties.maxResults());
        }

        if ("duckduckgo".equalsIgnoreCase(properties.provider())) {
            String html = restClient.get()
                    .uri(builder -> builder.path("/html/").queryParam("q", query).build())
                    .accept(MediaType.TEXT_HTML)
                    .retrieve()
                    .body(String.class);
            return parseDuckDuckGoResults(html == null ? "" : html, properties.maxResults());
        }

        throw new IllegalStateException("Unsupported web search provider: " + properties.provider());
    }

    private List<WebSearchResult> parseBingRssResults(String rss, int maxResults) {
        Matcher itemMatcher = RSS_ITEM_PATTERN.matcher(rss);
        List<WebSearchResult> results = new ArrayList<>();

        while (itemMatcher.find() && results.size() < maxResults) {
            String item = itemMatcher.group(1);
            String title = firstMatch(RSS_TITLE_PATTERN, item);
            String url = firstMatch(RSS_LINK_PATTERN, item);
            String snippet = firstMatch(RSS_DESCRIPTION_PATTERN, item);

            title = clean(title);
            url = normalizeUrl(url);
            snippet = clean(snippet);

            if (!title.isBlank() && !url.isBlank()) {
                results.add(new WebSearchResult(results.size() + 1, title, url, snippet));
            }
        }

        return List.copyOf(results);
    }

    private List<WebSearchResult> parseBingHtmlResults(String html, int maxResults) {
        List<WebSearchResult> results = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        Matcher markerMatcher = BING_MARKER_PATTERN.matcher(html);
        while (markerMatcher.find()) {
            starts.add(markerMatcher.start());
        }

        for (int i = 0; i < starts.size() && results.size() < maxResults; i++) {
            int start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) : Math.min(html.length(), start + 12000);
            String block = html.substring(start, Math.min(end, html.length()));
            Matcher linkMatcher = BING_LINK_PATTERN.matcher(block);
            if (!linkMatcher.find()) {
                continue;
            }

            String url = normalizeUrl(linkMatcher.group(1));
            String title = clean(linkMatcher.group(2));
            String snippet = "";
            Matcher snippetMatcher = BING_SNIPPET_PATTERN.matcher(block);
            if (snippetMatcher.find()) {
                snippet = clean(snippetMatcher.group(1));
            }

            if (!title.isBlank() && !url.isBlank()) {
                results.add(new WebSearchResult(results.size() + 1, title, url, snippet));
            }
        }

        if (results.isEmpty()) {
            Matcher linkMatcher = BING_LINK_PATTERN.matcher(html);
            while (linkMatcher.find() && results.size() < maxResults) {
                String url = normalizeUrl(linkMatcher.group(1));
                String title = clean(linkMatcher.group(2));
                String snippet = snippetAfter(html, linkMatcher.end(), linkMatcher.start());
                if (!title.isBlank() && !url.isBlank()) {
                    results.add(new WebSearchResult(results.size() + 1, title, url, snippet));
                }
            }
        }

        return List.copyOf(results);
    }

    private List<WebSearchResult> parseDuckDuckGoResults(String html, int maxResults) {
        Matcher resultMatcher = RESULT_PATTERN.matcher(html);
        List<WebSearchResult> results = new ArrayList<>();
        int cursor = 0;

        while (resultMatcher.find() && results.size() < maxResults) {
            String url = normalizeUrl(resultMatcher.group(1));
            String title = clean(resultMatcher.group(2));
            String snippet = snippetAfter(html, resultMatcher.end(), cursor);
            cursor = resultMatcher.end();

            if (!title.isBlank() && !url.isBlank()) {
                results.add(new WebSearchResult(results.size() + 1, title, url, snippet));
            }
        }

        return List.copyOf(results);
    }

    private String snippetAfter(String html, int start, int previousCursor) {
        int end = Math.min(html.length(), start + 2500);
        String window = html.substring(Math.max(previousCursor, start), end);
        Matcher snippetMatcher = SNIPPET_PATTERN.matcher(window);
        if (!snippetMatcher.find()) {
            return "";
        }
        String snippet = snippetMatcher.group(1) != null ? snippetMatcher.group(1) : snippetMatcher.group(2);
        return clean(snippet);
    }

    private String normalizeUrl(String rawUrl) {
        String url = HtmlUtils.htmlUnescape(rawUrl == null ? "" : rawUrl).trim();
        if (url.startsWith("//")) {
            url = "https:" + url;
        }
        int uddgIndex = url.indexOf("uddg=");
        if (uddgIndex >= 0) {
            int valueStart = uddgIndex + "uddg=".length();
            int valueEnd = url.indexOf('&', valueStart);
            String encoded = valueEnd >= 0 ? url.substring(valueStart, valueEnd) : url.substring(valueStart);
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        }
        if (url.startsWith("/")) {
            return properties.baseUrl() + url;
        }
        return url;
    }

    private String firstMatch(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String clean(String html) {
        String withoutTags = TAG_PATTERN.matcher(html == null ? "" : html).replaceAll(" ");
        return HtmlUtils.htmlUnescape(withoutTags)
                .replaceAll("\\s+", " ")
                .trim();
    }
}
