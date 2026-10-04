package com.example.rag.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.rag.config.LlmProperties;
import com.example.rag.model.Chunk;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是一个严谨的问答助手。请严格根据下面「参考资料」回答用户问题：
            1. 资料里有依据的，直接回答；
            2. 资料里没有的内容，明确说「根据现有资料无法回答」，不要编造；
            3. 回答使用与问题相同的语言。

            参考资料：
            %s""";

    /** 证据闸门拦截时的固定回复，不经过 LLM，保证措辞逐字确定 */
    private static final String REFUSAL = "根据现有资料无法回答这个问题。";

    private final WebClient webClient;

    private final LlmProperties llmProperties;

    private final RetrievalService retrievalService;

    public ChatService(WebClient webClient,
                       LlmProperties llmProperties,
                       RetrievalService retrievalService) {
        this.webClient = webClient;
        this.llmProperties = llmProperties;
        this.retrievalService = retrievalService;
    }

    public void streamChat(String question, SseEmitter emitter, boolean skipRetrieval) {
        List<Chunk> retrieved = List.of();
        RetrievalOutcome outcome = null;

        if (skipRetrieval) {
            log.info("用户已确认知识库不可用，跳过检索，按普通对话处理");
        } else {
            try {
                // 检索链路：向量化 → 候选召回 → Rerank → 截 topK → 证据闸门
                outcome = retrievalService.retrieve(question);
            } catch (Exception e) {
                // 请求中途才暴露的故障（DB 断开、embedding 失败）：通知前端后降级为裸 LLM
                log.error("检索链路失败，降级为普通对话", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("degraded")
                            .data(Map.of("reason", "知识库暂时不可用，以下回答未基于资料"),
                                    MediaType.APPLICATION_JSON));
                } catch (IOException ioe) {
                    emitter.completeWithError(ioe);
                    return;
                }
            }
        }

        if (outcome != null) {
            retrieved = outcome.getChunks();
            if (!outcome.isEvidenceReady()) {
                log.info("证据闸门拦截，固定拒答：top1={}",
                        String.format("%.3f", outcome.getTop1Score()));
                refuse(emitter);
                return;
            }
        }

        if (!retrieved.isEmpty()) {
            log.info("召回 {} 个分块，相似度：{}",
                    retrieved.size(),
                    retrieved.stream()
                            .map(c -> String.format("%.3f", c.getScore()))
                            .collect(Collectors.joining(", ")));
        }

        List<Map<String, String>> messages = new ArrayList<>();
        if (!retrieved.isEmpty()) {
            String context = buildContext(retrieved);
            messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT_TEMPLATE.formatted(context)));
        }
        messages.add(Map.of("role", "user", "content", question));

        Map<String, Object> requestBody = Map.of(
                "model", llmProperties.getModelName(),
                "stream", true,
                "messages", messages
        );

        Flux<String> flux = webClient.post()
                .uri(llmProperties.getBaseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + llmProperties.getApiKey())
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class);

        // 订阅句柄提升到与 emitter 同作用域：断连/超时/发送失败时取消，上游随即停止生成
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        // 区分正常结束与客户端中途断开：onCompletion 两种情况都会触发
        AtomicBoolean completedNormally = new AtomicBoolean(false);
        Disposable subscription = flux.subscribe(
                chunk -> {
                    try {
                        emitter.send(chunk);
                    } catch (IOException e) {
                        log.warn("SSE 发送失败，客户端可能已断开，取消上游订阅", e);
                        subscriptionRef.get().dispose();
                        emitter.completeWithError(e);
                    }
                },
                e -> {
                    log.error("上游 LLM 流式调用失败", e);
                    emitter.completeWithError(e);
                },
                () -> {
                    completedNormally.set(true);
                    emitter.complete();
                }
        );
        subscriptionRef.set(subscription);

        emitter.onCompletion(() -> {
            if (!completedNormally.get()) {
                log.info("客户端断开 SSE，取消上游订阅");
            }
            subscription.dispose();
        });
        emitter.onTimeout(() -> {
            log.warn("SSE 连接超时，取消上游订阅");
            subscription.dispose();
        });
        emitter.onError(e -> {
            log.debug("SSE 异步错误回调：{}", e.toString());
            subscription.dispose();
        });
    }

    /** 闸门拦截：按上游同款 OpenAI SSE 协议吐一条固定文本后收尾，前端无需感知闸门。 */
    private void refuse(SseEmitter emitter) {
        try {
            emitter.send(Map.of("choices", List.of(
                    Map.of("delta", Map.of("content", REFUSAL)))));
            emitter.send("[DONE]");
            emitter.complete();
        } catch (IOException e) {
            log.warn("拒答消息发送失败，客户端可能已断开", e);
            emitter.completeWithError(e);
        }
    }

    /** 把召回分块编号拼接成参考资料文本。 */
    private String buildContext(List<Chunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            sb.append('[').append(i + 1).append("] ").append(chunks.get(i).getContent()).append("\n\n");
        }
        return sb.toString().trim();
    }
}
