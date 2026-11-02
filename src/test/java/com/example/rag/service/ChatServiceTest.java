package com.example.rag.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.rag.config.LlmProperties;
import com.example.rag.model.Chunk;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ChatServiceTest {

    private WebClient webClient;

    private LlmProperties llmProperties;

    private RetrievalService retrievalService;

    private QueryRewriteService queryRewriteService;

    private RecommendedQuestionService recommendedQuestionService;

    private WebClient.RequestBodySpec bodySpec;

    private WebClient.ResponseSpec responseSpec;

    private ChatService service;

    @BeforeEach
    void setUp() {
        // WebClient 链式 API 含可变参数 header，RETURNS_DEEP_STUBS 无法正确返回中间 mock，手动逐层 mock
        webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), any(String[].class))).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToFlux(String.class)).thenReturn(Flux.just("chunk"));

        llmProperties = new LlmProperties();
        llmProperties.setBaseUrl("http://test");
        llmProperties.setApiKey("key");
        llmProperties.setModelName("model");
        retrievalService = mock(RetrievalService.class);
        // 默认改写直接回退原问题，既有测试行为不变
        queryRewriteService = mock(QueryRewriteService.class);
        when(queryRewriteService.rewrite(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        recommendedQuestionService = mock(RecommendedQuestionService.class);
        service = new ChatService(webClient, llmProperties, retrievalService, queryRewriteService, recommendedQuestionService);
    }

    @Test
    void SSE发送失败且订阅句柄未入槽时不抛NPE() throws IOException {
        // 同步源 Flux.just 在 subscribe() 内部就触发 onNext，此刻 subscriptionRef 尚未 set
        when(webClient.post()
                .uri(anyString())
                .header(anyString(), anyString())
                .header(anyString(), anyString())
                .bodyValue(any())
                .retrieve()
                .bodyToFlux(String.class))
                .thenReturn(Flux.just("chunk"));

        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("客户端断开")).when(emitter).send(any(String.class));

        // skipRetrieval=true 绕开检索，直接进入 LLM 流式分支
        assertDoesNotThrow(() -> service.streamChat("问题", emitter, true, List.of(), null, "test-trace", false));

        verify(emitter).completeWithError(any(IOException.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 检索命中时先下发来源事件再进入模型流() throws IOException {
        Chunk chunk = new Chunk(1L, 2, "分块正文", null);
        chunk.setDocumentName("learn.txt");
        chunk.setScore(0.612);
        when(retrievalService.retrieve(anyString(), anyString()))
                .thenReturn(new RetrievalOutcome(List.of(chunk), 0.612, true));

        SseEmitter emitter = mock(SseEmitter.class);
        service.streamChat("问题", emitter, false, List.of(), null, "test-trace", false);

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, atLeastOnce()).send(captor.capture());

        // 事件构建后拆成若干片段：文本片段里含 "event:sources"，数据片段保留原始 Map
        boolean namedSources = false;
        Map<String, Object> payload = null;
        for (ResponseBodyEmitter.DataWithMediaType part : captor.getAllValues().get(0).build()) {
            Object data = part.getData();
            if (data instanceof CharSequence text && text.toString().contains("event:sources")) {
                namedSources = true;
            } else if (data instanceof Map) {
                payload = (Map<String, Object>) data;
            }
        }

        assertTrue(namedSources, "首个 SSE 事件应为 sources");
        assertNotNull(payload, "sources 事件应携带数据");
        List<Map<String, Object>> sources = (List<Map<String, Object>>) payload.get("sources");
        assertEquals(1, sources.size());
        assertEquals(1, sources.get(0).get("index"));
        assertEquals("learn.txt", sources.get(0).get("documentName"));
        assertEquals("分块正文", sources.get(0).get("content"));
    }

    @Test
    void 证据闸门拦截时不下发来源也不调用模型() throws IOException {
        when(retrievalService.retrieve(anyString(), anyString()))
                .thenReturn(new RetrievalOutcome(List.of(), 0.1, false));

        SseEmitter emitter = mock(SseEmitter.class);
        service.streamChat("问题", emitter, false, List.of(), null, "test-trace", false);

        // 拦截走 refuse：只发 Object 类型的拒答文案，不发 SseEventBuilder 命名事件
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(webClient, never()).post();
    }

    @Test
    void 跳过检索时不下发来源事件() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        service.streamChat("问题", emitter, true, List.of(), null, "test-trace", false);

        verify(retrievalService, never()).retrieve(anyString(), anyString());
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void 来源事件发送失败时直接收尾且不调用模型() throws IOException {
        Chunk chunk = new Chunk(1L, 0, "正文", null);
        chunk.setDocumentName("learn.txt");
        chunk.setScore(0.612);
        when(retrievalService.retrieve(anyString(), anyString()))
                .thenReturn(new RetrievalOutcome(List.of(chunk), 0.612, true));

        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("客户端断开")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        service.streamChat("问题", emitter, false, List.of(), null, "test-trace", false);

        verify(emitter).completeWithError(any(IOException.class));
        verify(webClient, never()).post();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 系统提示词包含来源编号标注要求() throws IOException {
        Chunk chunk = new Chunk(1L, 0, "正文", null);
        chunk.setDocumentName("learn.txt");
        chunk.setScore(0.612);
        when(retrievalService.retrieve(anyString(), anyString()))
                .thenReturn(new RetrievalOutcome(List.of(chunk), 0.612, true));

        SseEmitter emitter = mock(SseEmitter.class);
        service.streamChat("问题", emitter, false, List.of(), null, "test-trace", false);

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(bodySpec).bodyValue(bodyCaptor.capture());

        Map<String, Object> body = (Map<String, Object>) bodyCaptor.getValue();
        List<Map<String, String>> messages = (List<Map<String, String>>) body.get("messages");
        String systemContent = messages.stream()
                .filter(m -> "system".equals(m.get("role")))
                .map(m -> m.get("content"))
                .findFirst()
                .orElseThrow();

        assertTrue(systemContent.contains("标注该资料的编号"),
                "系统提示词应要求回答时标注资料编号");
    }

    @Test
    @SuppressWarnings("unchecked")
    void history按顺序拼入messages且位于system与当前问题之间() throws IOException {
        Chunk chunk = new Chunk(1L, 0, "正文", null);
        chunk.setDocumentName("learn.txt");
        chunk.setScore(0.612);
        when(retrievalService.retrieve(anyString(), anyString()))
                .thenReturn(new RetrievalOutcome(List.of(chunk), 0.612, true));

        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "第一轮问题"),
                Map.of("role", "assistant", "content", "第一轮回答")
        );

        SseEmitter emitter = mock(SseEmitter.class);
        service.streamChat("当前问题", emitter, false, history, null, "test-trace", false);

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(bodySpec).bodyValue(bodyCaptor.capture());

        Map<String, Object> body = (Map<String, Object>) bodyCaptor.getValue();
        List<Map<String, String>> messages = (List<Map<String, String>>) body.get("messages");

        List<String> roles = messages.stream().map(m -> m.get("role")).toList();
        assertEquals(List.of("system", "user", "assistant", "user"), roles,
                "messages 顺序应为 system + history + 当前 user");
        assertEquals("第一轮问题", messages.get(1).get("content"));
        assertEquals("第一轮回答", messages.get(2).get("content"));
        assertEquals("当前问题", messages.get(3).get("content"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void history超阈值且未传summary时生成摘要并作为system拼入messages() throws IOException {
        Chunk chunk = new Chunk(1L, 0, "正文", null);
        chunk.setDocumentName("learn.txt");
        chunk.setScore(0.612);
        when(retrievalService.retrieve(anyString(), anyString()))
                .thenReturn(new RetrievalOutcome(List.of(chunk), 0.612, true));

        // 10 轮 = 20 条消息，超过 SUMMARY_TRIGGER_TURNS(8)
        List<Map<String, String>> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(Map.of("role", "user", "content", "问题" + i));
            history.add(Map.of("role", "assistant", "content", "回答" + i));
        }

        // mock 非流式摘要响应
        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.just("{\"choices\":[{\"message\":{\"content\":\"早期对话摘要\"}}]}"));

        SseEmitter emitter = mock(SseEmitter.class);
        service.streamChat("当前问题", emitter, false, history, null, "test-trace", false);

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        // 摘要请求 + 主对话请求，bodyValue 至少被调用 2 次
        verify(bodySpec, atLeast(2)).bodyValue(bodyCaptor.capture());

        // 取最后一次为给主对话的请求体
        Map<String, Object> body = (Map<String, Object>) bodyCaptor.getAllValues()
                .get(bodyCaptor.getAllValues().size() - 1);
        List<Map<String, String>> messages = (List<Map<String, String>>) body.get("messages");

        long systemCount = messages.stream().filter(m -> "system".equals(m.get("role"))).count();
        assertEquals(2, systemCount, "超阈值时应包含检索 system 与摘要 system 两条消息");

        String summaryContent = messages.stream()
                .filter(m -> "system".equals(m.get("role")))
                .map(m -> m.get("content"))
                .filter(c -> c.contains("conversation-summary"))
                .findFirst()
                .orElseThrow();
        assertTrue(summaryContent.contains("早期对话摘要"), "摘要 system 应包含生成的摘要文本");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 历史非空时检索用改写后的问题且用户消息仍是原问题() throws IOException {
        Chunk chunk = new Chunk(1L, 0, "正文", null);
        chunk.setDocumentName("learn.txt");
        chunk.setScore(0.612);
        when(retrievalService.retrieve(anyString(), anyString()))
                .thenReturn(new RetrievalOutcome(List.of(chunk), 0.612, true));

        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "Spring Boot 的自动配置是什么？"),
                Map.of("role", "assistant", "content", "自动配置是……")
        );
        when(queryRewriteService.rewrite("它怎么工作", history))
                .thenReturn("Spring Boot 的自动配置是怎么工作的？");

        SseEmitter emitter = mock(SseEmitter.class);
        service.streamChat("它怎么工作", emitter, false, history, null, "test-trace", false);

        // 检索用的是改写后的问题
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(retrievalService).retrieve(queryCaptor.capture(), eq("test-trace"));
        assertEquals("Spring Boot 的自动配置是怎么工作的？", queryCaptor.getValue(),
                "检索 query 应为改写后的完整问题");

        // 拼给 LLM 的用户消息仍是原问题，不改用户看到的内容
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(bodySpec).bodyValue(bodyCaptor.capture());
        Map<String, Object> body = (Map<String, Object>) bodyCaptor.getValue();
        List<Map<String, String>> messages = (List<Map<String, String>>) body.get("messages");
        String lastUserContent = messages.stream()
                .filter(m -> "user".equals(m.get("role")))
                .reduce((first, second) -> second)
                .map(m -> m.get("content"))
                .orElseThrow();
        assertEquals("它怎么工作", lastUserContent, "LLM messages 里的用户消息应为原问题");
    }
}
