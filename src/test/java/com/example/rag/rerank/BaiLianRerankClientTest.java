package com.example.rag.rerank;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.rag.config.RerankProperties;
import com.example.rag.model.Chunk;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

class BaiLianRerankClientTest {

    private MockWebServer server;

    private BaiLianRerankClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        RerankProperties properties = new RerankProperties();
        properties.setEnabled(true);
        properties.setModel("qwen3-rerank");
        properties.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        properties.setApiKey("test-key");

        client = new BaiLianRerankClient(
                org.springframework.web.reactive.function.client.WebClient.builder().build(),
                properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void rerank_按index映射回Chunk并按score降序() {
        // 百炼返回：第 1 块得分 0.9、第 0 块得分 0.3（3 候选取 2）
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "output": {
                            "results": [
                              {"index": 1, "relevance_score": 0.9},
                              {"index": 0, "relevance_score": 0.3}
                            ]
                          },
                          "usage": {"total_tokens": 10},
                          "request_id": "test-req-1"
                        }
                        """));

        Chunk c0 = new Chunk();
        c0.setContent("块零");
        c0.setScore(0.5);
        Chunk c1 = new Chunk();
        c1.setContent("块一");
        c1.setScore(0.8);
        Chunk c2 = new Chunk();
        c2.setContent("块二");
        c2.setScore(0.6);

        List<Chunk> result = client.rerank("问题", List.of(c0, c1, c2), 2);

        assertEquals(2, result.size());
        assertEquals("块一", result.get(0).getContent());
        assertEquals(0.9, result.get(0).getScore(), 0.001);
        assertEquals("块零", result.get(1).getContent());
        assertEquals(0.3, result.get(1).getScore(), 0.001);
    }

    @Test
    void rerank_候选数不超过topN时原样返回() {
        Chunk c0 = new Chunk();
        c0.setContent("唯一的块");

        // 候选 1 条、topN=3，不需要调 API
        List<Chunk> result = client.rerank("问题", List.of(c0), 3);

        assertEquals(1, result.size());
        assertEquals("唯一的块", result.get(0).getContent());
        // 没有发请求
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void rerank_响应为空时抛异常() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{}"));

        Chunk c0 = new Chunk();
        c0.setContent("块零");
        Chunk c1 = new Chunk();
        c1.setContent("块一");

        assertThrows(IllegalStateException.class,
                () -> client.rerank("问题", List.of(c0, c1), 1));
    }

    @Test
    void rerank_topN截断() {
        // 返回 3 条但 topN=1，只取第 1 条
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "output": {
                            "results": [
                              {"index": 2, "relevance_score": 0.95},
                              {"index": 1, "relevance_score": 0.7},
                              {"index": 0, "relevance_score": 0.1}
                            ]
                          },
                          "usage": {"total_tokens": 10},
                          "request_id": "test-req-2"
                        }
                        """));

        Chunk c0 = new Chunk();
        c0.setContent("块零");
        Chunk c1 = new Chunk();
        c1.setContent("块一");
        Chunk c2 = new Chunk();
        c2.setContent("块二");

        List<Chunk> result = client.rerank("问题", List.of(c0, c1, c2), 1);

        assertEquals(1, result.size());
        assertEquals("块二", result.get(0).getContent());
        assertEquals(0.95, result.get(0).getScore(), 0.001);
    }
}
