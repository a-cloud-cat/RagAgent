package com.example.rag.embedding;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.rag.config.EmbeddingProperties;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAiEmbeddingClient 单元测试：用 MockWebServer 在本地起假百炼服务，
 * 不联网、不花钱，验证请求体格式与条数/维度校验逻辑。
 */
class OpenAiEmbeddingClientTest {

    private MockWebServer server;

    private EmbeddingProperties properties;

    private OpenAiEmbeddingClient client;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        properties = new EmbeddingProperties();
        // server.url("/") 末尾带斜杠，去掉后与生产代码的 baseUrl + path 拼接方式保持一致
        properties.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        properties.setApiKey("test-key");
        properties.setModel("test-embedding-model");
        properties.setDimension(4);

        client = new OpenAiEmbeddingClient(WebClient.create(), properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void 正常响应_请求体含正确的model_input_dimension且鉴权头正确() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                {"data":[
                  {"index":0,"embedding":[0.1,0.2,0.3,0.4]},
                  {"index":1,"embedding":[0.5,0.6,0.7,0.8]}
                ]}
                """).addHeader("Content-Type", "application/json"));

        List<float[]> vectors = client.embed(List.of("第一句话", "第二句话"));

        // 返回结果按输入顺序对齐、维度正确
        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
        assertThat(vectors.get(1)).containsExactly(0.5f, 0.6f, 0.7f, 0.8f);

        // 校验实际发出的 HTTP 请求
        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).endsWith("/compatible-mode/v1/embeddings");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-key");

        JsonNode body = objectMapper.readTree(request.getBody().readUtf8());
        assertThat(body.get("model").asText()).isEqualTo("test-embedding-model");
        assertThat(body.get("dimension").asInt()).isEqualTo(4);
        assertThat(body.get("input")).hasSize(2);
        assertThat(body.get("input").get(0).asText()).isEqualTo("第一句话");
    }

    @Test
    void 返回条数与输入不一致_抛IllegalStateException() {
        // 输入 2 条，桩只返回 1 条
        server.enqueue(new MockResponse().setBody("""
                {"data":[{"index":0,"embedding":[0.1,0.2,0.3,0.4]}]}
                """).addHeader("Content-Type", "application/json"));

        assertThatThrownBy(() -> client.embed(List.of("第一句话", "第二句话")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("条数不一致");
    }

    @Test
    void 返回维度与配置不一致_抛IllegalStateException() {
        // 配置 4 维，桩返回 3 维
        server.enqueue(new MockResponse().setBody("""
                {"data":[{"index":0,"embedding":[0.1,0.2,0.3]}]}
                """).addHeader("Content-Type", "application/json"));

        assertThatThrownBy(() -> client.embed(List.of("第一句话")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("维度不一致");
    }

    @Test
    void 未配置ApiKey_不发请求直接抛IllegalStateException() {
        properties.setApiKey("  ");
        OpenAiEmbeddingClient noKeyClient = new OpenAiEmbeddingClient(WebClient.create(), properties);

        assertThatThrownBy(() -> noKeyClient.embed(List.of("第一句话")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API-KEY 未配置");
        // 没有 enqueue 任何响应；若误发请求，takeRequest 会在下面失败
        assertThat(server.getRequestCount()).isZero();
    }
}
