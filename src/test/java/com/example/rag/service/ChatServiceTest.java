package com.example.rag.service;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.rag.config.LlmProperties;

import reactor.core.publisher.Flux;

class ChatServiceTest {

    private WebClient webClient;

    private LlmProperties llmProperties;

    private ChatService service;

    @BeforeEach
    void setUp() {
        // WebClient 链式 API 含可变参数 header，RETURNS_DEEP_STUBS 无法正确返回中间 mock，手动逐层 mock
        webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

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
        service = new ChatService(webClient, llmProperties, mock(RetrievalService.class));
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
        assertDoesNotThrow(() -> service.streamChat("问题", emitter, true));

        verify(emitter).completeWithError(any(IOException.class));
    }
}
