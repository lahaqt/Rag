package com.example.ragagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.model.DocumentChunk;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentChunkerTests {

    @Test
    void recursiveChunkingPrefersSemanticBoundaries() {
        DocumentChunker chunker = new DocumentChunker(properties("recursive", 80, 10));

        List<DocumentChunk> chunks = chunker.chunk(
                "doc-1",
                "policy.txt",
                "kb-1",
                """
                        一、退货政策
                        自签收之日起 7 天内，商品未经使用且不影响二次销售的，消费者可申请七天无理由退货。

                        二、换货政策
                        自签收之日起 15 天内，商品存在质量问题的，消费者可申请免费换货。
                        """
        );

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks.get(0).getContent()).contains("退货政策");
        assertThat(chunks.get(1).getContent()).contains("换货政策");
    }

    @Test
    void hybridChunkingKeepsFaqPairsTogether() {
        DocumentChunker chunker = new DocumentChunker(properties("hybrid", 120, 12));

        List<DocumentChunk> chunks = chunker.chunk(
                "doc-1",
                "faq.txt",
                "kb-1",
                """
                        Q: 生鲜商品支持七天无理由退货吗？
                        A: 不支持。生鲜食品属于特殊品类，具体以商品详情页标注为准。

                        Q: 换货需要什么材料？
                        A: 需要订单编号、商品照片及问题描述。
                        """
        );

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getContent()).contains("Q: 生鲜商品").contains("A: 不支持");
        assertThat(chunks.get(1).getContent()).contains("Q: 换货").contains("A: 需要订单编号");
    }

    private RagProperties properties(String strategy, int chunkSize, int chunkOverlap) {
        return new RagProperties(
                Path.of("data/knowledge-bases"),
                chunkSize,
                chunkOverlap,
                new RagProperties.Chunking(strategy, chunkSize, chunkOverlap, 20, List.of("\n\n", "\n", "。", "，", " ", "")),
                null,
                null,
                null,
                null,
                null
        );
    }
}
