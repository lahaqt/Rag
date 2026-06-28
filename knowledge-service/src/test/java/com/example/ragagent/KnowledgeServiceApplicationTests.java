package com.example.ragagent;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "rag.metadata.provider=memory",
        "rag.object-storage.provider=local",
        "rag.queue.provider=none",
        "rag.vector.embedding.provider=hash",
        "rag.vector.embedding.dimensions=64",
        "rag.vector.store.provider=memory"
})
@AutoConfigureMockMvc
class KnowledgeServiceApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listsSeedKnowledgeBases() throws Exception {
        mockMvc.perform(get("/api/knowledge-bases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$[0].id", notNullValue()));
    }

    @Test
    void uploadsDocumentAndReturnsTikaParsedChunks() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "policy.txt",
                "text/plain",
                "Document management module parses files with Apache Tika and exposes chunks.".getBytes()
        );

        String response = mockMvc.perform(multipart("/api/knowledge-bases/enterprise-policy/documents").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.status").value("PARSED"))
                .andExpect(jsonPath("$.chunkCount", greaterThanOrEqualTo(1)))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        String documentId = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        mockMvc.perform(get("/api/knowledge-bases/enterprise-policy/documents/{documentId}/chunks", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$[0].content", notNullValue()));
    }

    @Test
    void indexesAndSearchesDocumentVectors() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "expenses.txt",
                "text/plain",
                "Employees submit travel expenses in the finance portal with receipts attached.".getBytes()
        );

        String response = mockMvc.perform(multipart("/api/knowledge-bases/enterprise-policy/documents").file(file))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String documentId = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/knowledge-bases/enterprise-policy/documents/{documentId}/reindex", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUESTED"));

        mockMvc.perform(post("/api/vector/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "knowledgeBaseId": "enterprise-policy",
                                  "query": "travel expense receipts",
                                  "topK": 3,
                                  "similarityThreshold": 0.0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches.length()", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.matches[0].documentId").value(documentId));

        mockMvc.perform(get("/api/vector/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeProvider").value("memory"))
                .andExpect(jsonPath("$.embeddingProvider").value("hash"))
                .andExpect(jsonPath("$.vectorCount", greaterThanOrEqualTo(1)));
    }

    @Test
    void searchesExactTermsWithBm25() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "gpu.txt",
                "text/plain",
                "The RTX 4090 graphics card has a 450W power draw in the product specification.".getBytes()
        );

        String response = mockMvc.perform(multipart("/api/knowledge-bases/enterprise-policy/documents").file(file))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String documentId = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/vector/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "knowledgeBaseId": "enterprise-policy",
                                  "query": "RTX 4090 功耗",
                                  "topK": 3,
                                  "retrievalMode": "bm25",
                                  "queryExpansionEnabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches.length()", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.matches[0].documentId").value(documentId));
    }

    @Test
    void parsesMarkdownFrontmatterIntoMetadata() throws Exception {
        String markdown = """
                ---
                title: "检索增强生成概念"
                category: RAG 基础
                difficulty: 入门
                tags: [RAG, 检索增强, LLM]
                ---

                # 检索增强生成

                RAG 把外部知识检索与生成结合，缓解大模型的幻觉问题。
                """.strip();

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "overview.md",
                "text/markdown",
                markdown.getBytes(StandardCharsets.UTF_8)
        );

        String response = mockMvc.perform(multipart("/api/knowledge-bases/enterprise-policy/documents").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARSED"))
                .andExpect(jsonPath("$.metadata.title").value("检索增强生成概念"))
                .andExpect(jsonPath("$.metadata.category").value("RAG 基础"))
                .andExpect(jsonPath("$.metadata.difficulty").value("入门"))
                .andExpect(jsonPath("$.metadata.tags").value("RAG, 检索增强, LLM"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        String documentId = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        mockMvc.perform(get("/api/knowledge-bases/enterprise-policy/documents/{documentId}/chunks", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$[0].content", not(containsString("title:"))))
                .andExpect(jsonPath("$[0].content", not(containsString("---"))))
                .andExpect(jsonPath("$[0].content", containsString("检索增强生成")));
    }
}
