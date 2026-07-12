package com.example.ragagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragagent.dto.RetrievalHit;
import org.junit.jupiter.api.Test;

class CitationExcerptExtractorTests {
    private final CitationExcerptExtractor extractor = new CitationExcerptExtractor();

    @Test
    void extractsTheQuestionRelevantSentenceInsteadOfTheChunkHeading() {
        RetrievalHit hit = new RetrievalHit(
                1,
                "kb-1",
                "doc-1",
                "chunk-1",
                0,
                "after-sales.md",
                "# 售后服务体系\n## 概述\n售后服务用于保障消费者权益。\n"
                        + "## 退款服务\n退款服务包括订单取消退款、退货退款和仅退款。\n"
                        + "联系客服前请准备订单号和相关凭证。",
                0.95
        );

        String excerpt = extractor.extract(hit, "退款流程有哪些", "退款流程", "可申请订单取消退款或退货退款。[1]");

        assertThat(excerpt).isEqualTo("退款服务包括订单取消退款、退货退款和仅退款。");
        assertThat(excerpt).doesNotContain("售后服务体系");
    }
}
