package com.example.ragagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FrontmatterExtractorTests {

    private final FrontmatterExtractor extractor = new FrontmatterExtractor();

    @Test
    void extractsScalarsAndStripsFrontmatterFromBody() {
        String text = """
                ---
                title: "混合检索"
                category: 检索与召回
                difficulty: 进阶
                ---

                # 混合检索

                正文从这里开始。
                """;

        FrontmatterExtractor.FrontmatterResult result = extractor.extract(text);

        assertThat(result.metadata())
                .containsEntry("title", "混合检索")
                .containsEntry("category", "检索与召回")
                .containsEntry("difficulty", "进阶");
        assertThat(result.body()).doesNotContain("---").doesNotContain("title:");
        assertThat(result.body()).contains("正文从这里开始。");
    }

    @Test
    void flattensListFieldsToCommaSeparatedString() {
        String text = """
                ---
                title: 概览
                tags: [RAG, 检索增强生成, LLM]
                keywords:
                  - Retrieval
                  - Augmented
                ---

                正文。
                """;

        FrontmatterExtractor.FrontmatterResult result = extractor.extract(text);

        assertThat(result.metadata().get("tags")).isEqualTo("RAG, 检索增强生成, LLM");
        assertThat(result.metadata().get("keywords")).isEqualTo("Retrieval, Augmented");
    }

    @Test
    void returnsTextUntouchedWhenNoFrontmatter() {
        String text = "一段没有任何 frontmatter 的纯文本。\n第二行。";

        FrontmatterExtractor.FrontmatterResult result = extractor.extract(text);

        assertThat(result.metadata()).isEmpty();
        assertThat(result.body()).isEqualTo(text);
    }

    @Test
    void degradesGracefullyOnMalformedFrontmatter() {
        // Unclosed flow sequence forces SnakeYAML to throw; the body must survive intact.
        String text = """
                ---
                title: [unclosed bracket
                ---

                正文。
                """;

        FrontmatterExtractor.FrontmatterResult result = extractor.extract(text);

        // Either the block parses partially or it falls back entirely; either way
        // parsing must not throw and the body must retain the prose.
        assertThat(result.body()).contains("正文。");
    }

    @Test
    void toleratesLeadingBomAndWhitespace() {
        String text = "﻿---\ntitle: 带 BOM\n---\n\n正文。\n";

        FrontmatterExtractor.FrontmatterResult result = extractor.extract(text);

        assertThat(result.metadata()).containsEntry("title", "带 BOM");
        assertThat(result.body()).contains("正文。");
    }

    @Test
    void firstH1TitleReturnsFirstHeading() {
        assertThat(extractor.firstH1Title("# 标题一\n\n正文\n## 子标题")).isEqualTo("标题一");
        assertThat(extractor.firstH1Title("仅正文，没有标题")).isNull();
        assertThat(extractor.firstH1Title("## 二级标题")).isNull();
    }

    @Test
    void emptyAndNullInputsAreSafe() {
        assertThat(extractor.extract("").body()).isEqualTo("");
        assertThat(extractor.extract(null).body()).isEqualTo("");
        assertThat(extractor.firstH1Title(null)).isNull();
    }

    @Test
    void nestedMapsAreSkippedButScalarsKept() {
        String text = """
                ---
                title: 文档
                nested:
                  a: 1
                  b: 2
                ---

                正文。
                """;

        Map<String, String> metadata = extractor.extract(text).metadata();

        assertThat(metadata).containsKey("title");
        assertThat(metadata).doesNotContainKey("nested");
    }
}
