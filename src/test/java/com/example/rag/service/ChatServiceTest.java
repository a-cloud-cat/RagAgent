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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.rag.config.LlmProperties;
import com.example.rag.config.MemoryProperties;
import com.example.rag.model.ChatMessage;
import com.example.rag.model.ChatSession;
import com.example.rag.model.Chunk;
import com.example.rag.service.intent.IntentResult;
import com.example.rag.service.intent.IntentRouter;
import com.example.rag.tool.CategoryTool;
import com.example.rag.tool.OrderTool;
import com.example.rag.tool.StockTool;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;

class ChatServiceTest {

    private WebClient webClient;

    private LlmProperties llmProperties;

    private RetrievalService retrievalService;

    private QueryRewriteService queryRewriteService;

    private RecommendedQuestionService recommendedQuestionService;

    private IntentRouter intentRouter;

    private OrderTool orderTool;

    private StockTool stockTool;

    private CategoryTool categoryTool;

    private MemoryService memoryService;

    private MemoryProperties memoryProperties;

    private LlmCallRecorder llmCallRecorder;

    private WebClient.RequestBodySpec bodySpec;

    private WebClient.ResponseSpec responseSpec;

    private ChatService service;

    private ChatSession session;

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
        // 意图识别默认走 RAG_QUERY，既有测试行为不变
        intentRouter = mock(IntentRouter.class);
        when(intentRouter.classify(anyString())).thenReturn(IntentResult.fallback());
        orderTool = mock(OrderTool.class);
        stockTool = mock(StockTool.class);
        categoryTool = mock(CategoryTool.class);

        // 记忆默认空上下文：不注入历史、无摘要，既有测试行为不变
        memoryService = mock(MemoryService.class);
        when(memoryService.loadContext(any())).thenReturn(new MemoryContext(null, List.of(), 0, 0));
        when(memoryService.pendingMessageCount(any())).thenReturn(0L);
        memoryProperties = new MemoryProperties();
        llmCallRecorder = mock(LlmCallRecorder.class);

        session = new ChatSession();
        session.setId(1L);
        session.setSessionKey("sess-1");
        session.setUserId("user-1");

        service = new ChatService(webClient, llmProperties, retrievalService, queryRewriteService,
                recommendedQuestionService, intentRouter, orderTool, stockTool, categoryTool,
                memoryService, memoryProperties, new ObjectMapper(), llmCallRecorder);
    }

    @Test
    void SSE发送失败且订阅句柄未入槽时不抛NPE() throws IOException {
        // 同步源 Flux.just 在 subscribe() 内部就触发 onNext，此刻 subscriptionRef 尚未 set
        when(responseSpec.bodyToFlux(String.class))
                .thenReturn(Flux.just("{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}"));

        SseEmitter emitter = mock(SseEmitter.class);
        // 同时 mock send(String) 和 send(SseEventBuilder)；首个 session 事件即失败，链路直接收尾
        doThrow(new IOException("客户端断开")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        doThrow(new IOException("客户端断开")).when(emitter).send(any(String.class));

        assertDoesNotThrow(() -> service.streamChat("问题", emitter, true, session, "test-trace", false, null));

        verify(emitter).completeWithError(any(IOException.class));
    }

    @Test
    void 检索命中时下发来源事件() throws IOException {
        Chunk chunk = new Chunk(1L, 2, "分块正文", null);
        chunk.setDocumentName("learn.txt");
        chunk.setScore(0.612);
        when(retrievalService.retrieve(anyString(), anyString(), any()))
                .thenReturn(new RetrievalOutcome(List.of(chunk), 0.612, true));

        SseEmitter emitter = mock(SseEmitter.class);
        service.streamChat("问题", emitter, false, session, "test-trace", false, null);

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, org.mockito.Mockito.atLeastOnce()).send(captor.capture());

        // 首个事件是 session，来源事件在其后，遍历找到 event:sources
        boolean namedSources = false;
        Map<String, Object> payload = null;
        for (SseEmitter.SseEventBuilder builder : captor.getAllValues()) {
            for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
                Object data = part.getData();
                if (data instanceof CharSequence text && text.toString().contains("event:sources")) {
                    namedSources = true;
                } else if (namedSources && data instanceof Map) {
                    payload = (Map<String, Object>) data;
                }
            }
            if (namedSources && payload != null) {
                break;
            }
        }

        assertTrue(namedSources, "应下发 sources 事件");
        assertNotNull(payload, "sources 事件应携带数据");
        List<Map<String, Object>> sources = (List<Map<String, Object>>) payload.get("sources");
        assertEquals(1, sources.size());
        assertEquals(1, sources.get(0).get("index"));
        assertEquals("learn.txt", sources.get(0).get("documentName"));
        assertEquals("分块正文", sources.get(0).get("content"));
    }

    @Test
    void 证据闸门拦截时不下发来源也不调用模型() throws IOException {
        when(retrievalService.retrieve(anyString(), anyString(), any()))
                .thenReturn(new RetrievalOutcome(List.of(), 0.1, false));

        SseEmitter emitter = mock(SseEmitter.class);
        service.streamChat("问题", emitter, false, session, "test-trace", false, null);

        // 拦截走 refuse：发 event:content + event:done，验证点是"不调 LLM"
        verify(webClient, never()).post();
    }

    @Test
    void 跳过检索时不下发来源事件() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        service.streamChat("问题", emitter, true, session, "test-trace", false, null);

        verify(retrievalService, never()).retrieve(anyString(), anyString(), any());
    }

    @Test
    void 来源事件发送失败时直接收尾且不调用模型() throws IOException {
        Chunk chunk = new Chunk(1L, 0, "正文", null);
        chunk.setDocumentName("learn.txt");
        chunk.setScore(0.612);
        when(retrievalService.retrieve(anyString(), anyString(), any()))
                .thenReturn(new RetrievalOutcome(List.of(chunk), 0.612, true));

        SseEmitter emitter = mock(SseEmitter.class);
        // 首个 session 事件成功，来源事件失败
        doNothing().doThrow(new IOException("客户端断开"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        service.streamChat("问题", emitter, false, session, "test-trace", false, null);

        verify(emitter).completeWithError(any(IOException.class));
        verify(webClient, never()).post();
    }

    @Test
    void 首个事件是session且携带sessionId() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        service.streamChat("问题", emitter, true, session, "test-trace", false, null);

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, org.mockito.Mockito.atLeastOnce()).send(captor.capture());

        boolean namedSession = false;
        Map<String, Object> payload = null;
        for (ResponseBodyEmitter.DataWithMediaType part : captor.getAllValues().get(0).build()) {
            Object data = part.getData();
            if (data instanceof CharSequence text && text.toString().contains("event:session")) {
                namedSession = true;
            } else if (data instanceof Map) {
                payload = (Map<String, Object>) data;
            }
        }
        assertTrue(namedSession, "首个 SSE 事件应为 session");
        assertNotNull(payload);
        assertEquals("sess-1", payload.get("sessionId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 系统提示词包含来源编号标注要求() throws IOException {
        Chunk chunk = new Chunk(1L, 0, "正文", null);
        chunk.setDocumentName("learn.txt");
        chunk.setScore(0.612);
        when(retrievalService.retrieve(anyString(), anyString(), any()))
                .thenReturn(new RetrievalOutcome(List.of(chunk), 0.612, true));

        SseEmitter emitter = mock(SseEmitter.class);
        service.streamChat("问题", emitter, false, session, "test-trace", false, null);

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
    void 上下文来自会话且位于system与当前问题之间() throws IOException {
        Chunk chunk = new Chunk(1L, 0, "正文", null);
        chunk.setDocumentName("learn.txt");
        chunk.setScore(0.612);
        when(retrievalService.retrieve(anyString(), anyString(), any()))
                .thenReturn(new RetrievalOutcome(List.of(chunk), 0.612, true));
        when(memoryService.loadContext(session)).thenReturn(new MemoryContext(null, List.of(
                chatMessage(1L, "user", "第一轮问题"),
                chatMessage(2L, "assistant", "第一轮回答"),
                chatMessage(3L, "user", "   ")), 1, 20));

        SseEmitter emitter = mock(SseEmitter.class);
        service.streamChat("当前问题", emitter, false, session, "test-trace", false, null);

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(bodySpec).bodyValue(bodyCaptor.capture());

        Map<String, Object> body = (Map<String, Object>) bodyCaptor.getValue();
        List<Map<String, String>> messages = (List<Map<String, String>>) body.get("messages");

        List<String> roles = messages.stream().map(m -> m.get("role")).toList();
        assertEquals(List.of("system", "user", "assistant", "user"), roles,
                "messages 顺序应为 system + 会话历史 + 当前 user，空白消息被过滤");
        assertEquals("第一轮问题", messages.get(1).get("content"));
        assertEquals("第一轮回答", messages.get(2).get("content"));
        assertEquals("当前问题", messages.get(3).get("content"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 摘要从会话读取并作为system注入() throws IOException {
        Chunk chunk = new Chunk(1L, 0, "正文", null);
        chunk.setDocumentName("learn.txt");
        chunk.setScore(0.612);
        when(retrievalService.retrieve(anyString(), anyString(), any()))
                .thenReturn(new RetrievalOutcome(List.of(chunk), 0.612, true));
        when(memoryService.loadContext(session)).thenReturn(new MemoryContext(
                "早期对话摘要", List.of(chatMessage(9L, "user", "最近一轮问题")), 0, 6));

        SseEmitter emitter = mock(SseEmitter.class);
        service.streamChat("当前问题", emitter, false, session, "test-trace", false, null);

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(bodySpec).bodyValue(bodyCaptor.capture());

        Map<String, Object> body = (Map<String, Object>) bodyCaptor.getValue();
        List<Map<String, String>> messages = (List<Map<String, String>>) body.get("messages");

        long systemCount = messages.stream().filter(m -> "system".equals(m.get("role"))).count();
        assertEquals(2, systemCount, "应包含检索 system 与摘要 system 两条消息");

        String summaryContent = messages.stream()
                .filter(m -> "system".equals(m.get("role")))
                .map(m -> m.get("content"))
                .filter(c -> c.contains("conversation-summary"))
                .findFirst()
                .orElseThrow();
        assertTrue(summaryContent.contains("早期对话摘要"), "摘要 system 应包含会话中的摘要文本");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 既有历史时检索用改写后的问题且用户消息仍是原问题() throws IOException {
        Chunk chunk = new Chunk(1L, 0, "正文", null);
        chunk.setDocumentName("learn.txt");
        chunk.setScore(0.612);
        when(retrievalService.retrieve(anyString(), anyString(), any()))
                .thenReturn(new RetrievalOutcome(List.of(chunk), 0.612, true));
        when(memoryService.loadContext(session)).thenReturn(new MemoryContext(null, List.of(
                chatMessage(1L, "user", "Spring Boot 的自动配置是什么？"),
                chatMessage(2L, "assistant", "自动配置是……")), 0, 30));
        when(queryRewriteService.rewrite("它怎么工作", List.of(
                Map.of("role", "user", "content", "Spring Boot 的自动配置是什么？"),
                Map.of("role", "assistant", "content", "自动配置是……"))))
                .thenReturn("Spring Boot 的自动配置是怎么工作的？");

        SseEmitter emitter = mock(SseEmitter.class);
        service.streamChat("它怎么工作", emitter, false, session, "test-trace", false, null);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(retrievalService).retrieve(queryCaptor.capture(), eq("test-trace"), any());
        assertEquals("Spring Boot 的自动配置是怎么工作的？", queryCaptor.getValue(),
                "检索 query 应为改写后的完整问题");

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

    @Test
    void 问答结束后用户问题与助手回答都落库() throws IOException {
        when(responseSpec.bodyToFlux(String.class))
                .thenReturn(Flux.just("{\"choices\":[{\"delta\":{\"content\":\"回答正文\"}}]}"));

        SseEmitter emitter = mock(SseEmitter.class);
        service.streamChat("当前问题", emitter, true, session, "test-trace", false, null);

        verify(memoryService).appendUserMessage(session, "当前问题");
        // 落库在弹性线程池异步执行，用 timeout 等待
        verify(memoryService, timeout(2000)).appendAssistantMessage(eq(session), eq("回答正文"), isNull());
    }

    private ChatMessage chatMessage(long id, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
