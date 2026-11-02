package com.example.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.rag.config.LlmProperties;

import reactor.core.publisher.Mono;

class RecommendedQuestionServiceTest {

    private WebClient webClient;
    private WebClient.RequestBodyUriSpec uriSpec;
    private WebClient.RequestBodySpec bodySpec;
    private WebClient.RequestHeadersSpec headersSpec;
    private WebClient.ResponseSpec responseSpec;
    private RecommendedQuestionService service;

    @BeforeEach
    void setUp() {
        webClient = mock(WebClient.class);
        uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        bodySpec = mock(WebClient.RequestBodySpec.class);
        headersSpec = mock(WebClient.RequestHeadersSpec.class);
        responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), any(String[].class))).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);

        LlmProperties props = new LlmProperties();
        props.setBaseUrl("http://test");
        props.setApiKey("key");
        props.setModelName("model");
        service = new RecommendedQuestionService(webClient, props);
    }

    @Test
    void LLM返回合法JSON数组时解析出推荐问题() {
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(
                "{\"choices\":[{\"message\":{\"content\":\"[\\\"问题1\\\",\\\"问题2\\\"]\"}}]}"));

        List<String> result = service.recommend("问题", "回答");

        assertEquals(2, result.size());
        assertEquals("问题1", result.get(0));
        assertEquals("问题2", result.get(1));
    }

    @Test
    void LLM输出带代码块标记时仍能解析() {
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(
                "{\"choices\":[{\"message\":{\"content\":\"```json\\n[\\\"追问1\\\"]\\n```\"}}]}"));

        List<String> result = service.recommend("问题", "回答");

        assertEquals(1, result.size());
        assertEquals("追问1", result.get(0));
    }

    @Test
    void 回答为空时直接返回空列表不调用LLM() {
        List<String> result = service.recommend("问题", "");

        assertTrue(result.isEmpty());
    }

    @Test
    void LLM调用异常时返回空列表不抛错() {
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.error(new RuntimeException("boom")));

        List<String> result = service.recommend("问题", "回答");

        assertTrue(result.isEmpty());
    }
}
