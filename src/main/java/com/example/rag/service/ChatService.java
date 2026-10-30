package com.example.rag.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import reactor.core.publisher.Mono;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    // history 保留最近 N 轮，超出从前面丢弃；1 轮 = 1 条 user + 1 条 assistant
    private static final int MAX_HISTORY_TURNS = 10;

    // history 超过该轮数时触发摘要，用摘要替代早期原文
    private static final int SUMMARY_TRIGGER_TURNS = 8;
    // 摘要生成后，最近 N 轮仍保留原文，保证近期细节不丢
    private static final int RECENT_KEEP_TURNS = 4;
    private static final int SUMMARY_MAX_CHARS = 200;

    private static final String SUMMARY_PROMPT = """
            你是会话记忆摘要器，把多轮对话浓缩成话题索引。
            只记：具体话题、处理状态、用户明确提出的约束条件
            不记：具体答案、数据、规则细节（问答助手每次实时检索最新资料，摘要里的答案会与之冲突）
            单行输出，不超过 %d 字。
            格式：用户咨询了【话题1】（状态）、【话题2】（状态）。约束：…。关键词：…""".formatted(SUMMARY_MAX_CHARS);

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是一个严谨的问答助手。请严格根据下面「参考资料」回答用户问题：
            1. 资料里有依据的，直接回答；
            2. 资料里没有的内容，明确说「根据现有资料无法回答」，不要编造；
            3. 引用某条资料时，在对应语句末尾标注该资料的编号，如 [1]；
            4. 回答使用与问题相同的语言；
            5. 结合对话上下文回答，若上下文与参考资料冲突，以参考资料为准。

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

    public void streamChat(String question, SseEmitter emitter, boolean skipRetrieval,
                           List<Map<String, String>> history, String summary) {
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
            // 先下发来源再流式回答：前端据此在正文旁挂可展开的原文，编号与 prompt 里的 [n] 一致
            try {
                emitter.send(SseEmitter.event()
                        .name("sources")
                        .data(Map.of("sources", buildSources(retrieved)), MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                log.warn("来源事件发送失败，客户端可能已断开", e);
                emitter.completeWithError(e);
                return;
            }
        }

        List<Map<String, String>> truncated = truncateHistory(history);
        int historyTurns = truncated.size() / 2;

        // history 超阈值时用摘要替代早期原文，近期保留 RECENT_KEEP_TURNS 轮
        String effectiveSummary = summary;
        List<Map<String, String>> recentHistory = truncated;
        if (historyTurns > SUMMARY_TRIGGER_TURNS) {
            int recentCount = RECENT_KEEP_TURNS * 2;
            List<Map<String, String>> earlyMessages = truncated.subList(0, truncated.size() - recentCount);
            recentHistory = new ArrayList<>(truncated.subList(truncated.size() - recentCount, truncated.size()));
            if (effectiveSummary == null || effectiveSummary.isBlank()) {
                effectiveSummary = generateSummary(earlyMessages, emitter);
            }
        }

        List<Map<String, String>> messages = new ArrayList<>();
        if (!retrieved.isEmpty()) {
            String context = buildContext(retrieved);
            messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT_TEMPLATE.formatted(context)));
        }
        // 摘要作为 system 消息注入，用标签包裹便于模型识别边界，不与检索 system 冲突
        if (effectiveSummary != null && !effectiveSummary.isBlank()) {
            messages.add(Map.of("role", "system", "content",
                    "<conversation-summary>\n" + effectiveSummary + "\n</conversation-summary>"));
        }
        messages.addAll(recentHistory);
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
                        // subscribe() 返回前同步源可能已触发回调，此时句柄尚未入槽
                        Disposable current = subscriptionRef.get();
                        if (current != null) {
                            current.dispose();
                        }
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

    /** 把召回分块转成前端可展示的来源条目，index 与 buildContext 的编号保持一致。 */
    private List<Map<String, Object>> buildSources(List<Chunk> chunks) {
        List<Map<String, Object>> sources = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("index", i + 1);
            source.put("documentName", chunk.getDocumentName());
            source.put("chunkIndex", chunk.getChunkIndex());
            source.put("score", chunk.getScore());
            source.put("content", chunk.getContent());
            sources.add(source);
        }
        return sources;
    }

    /** 把召回分块编号拼接成参考资料文本。 */
    private String buildContext(List<Chunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            sb.append('[').append(i + 1).append("] ").append(chunks.get(i).getContent()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /** history 超长截断：保留最近 MAX_HISTORY_TURNS 轮，超出从前面丢；截断后若首条是 assistant 再补丢一条，保证以 user 开头。 */
    private List<Map<String, String>> truncateHistory(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> result = new ArrayList<>(history);
        int maxMessages = MAX_HISTORY_TURNS * 2;
        while (result.size() > maxMessages) {
            result.remove(0);
        }
        // 保证首条是 user，避免 LLM API 报角色交替错误
        while (!result.isEmpty() && "assistant".equals(result.get(0).get("role"))) {
            result.remove(0);
        }
        return result;
    }

    /** 用 LLM 把早期对话压成摘要，通过 event:summary 下发前端持久化；失败返回 null，调用方回退为全量原文。 */
    private String generateSummary(List<Map<String, String>> earlyMessages, SseEmitter emitter) {
        List<Map<String, String>> summaryMessages = new ArrayList<>();
        summaryMessages.add(Map.of("role", "system", "content", SUMMARY_PROMPT));
        summaryMessages.addAll(earlyMessages);
        summaryMessages.add(Map.of("role", "user", "content", "请输出对话摘要。"));

        Map<String, Object> requestBody = Map.of(
                "model", llmProperties.getModelName(),
                "stream", false,
                "messages", summaryMessages
        );

        try {
            String response = webClient.post()
                    .uri(llmProperties.getBaseUrl() + "/chat/completions")
                    .header("Authorization", "Bearer " + llmProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (response == null) {
                return null;
            }
            // 非流式响应：{"choices":[{"message":{"content":"..."}}]}
            String content = extractContent(response);
            if (content == null || content.isBlank()) {
                return null;
            }
            log.info("生成对话摘要，{} 字", content.length());
            try {
                emitter.send(SseEmitter.event()
                        .name("summary")
                        .data(Map.of("summary", content), MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                log.warn("摘要事件发送失败，客户端可能已断开", e);
            }
            return content;
        } catch (Exception e) {
            log.warn("对话摘要生成失败，回退为全量原文", e);
            return null;
        }
    }

    /** 从非流式 LLM 响应中提取 message.content。 */
    private String extractContent(String response) {
        try {
            // 用 Jackson 解析，但项目未显式依赖 ObjectMapper 注入，走字符串查找避免新增依赖
            int contentIdx = response.indexOf("\"content\":\"");
            if (contentIdx < 0) {
                return null;
            }
            int start = contentIdx + "\"content\":\"".length();
            int end = response.indexOf("\"", start);
            if (end < 0) {
                return null;
            }
            return response.substring(start, end)
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"");
        } catch (Exception e) {
            return null;
        }
    }
}
