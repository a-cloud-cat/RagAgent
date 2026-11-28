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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.example.rag.config.LlmProperties;
import com.example.rag.config.MemoryProperties;
import com.example.rag.model.ChatMessage;
import com.example.rag.model.ChatSession;
import com.example.rag.model.Chunk;
import com.example.rag.service.intent.Intent;
import com.example.rag.service.intent.IntentResult;
import com.example.rag.service.intent.IntentRouter;
import com.example.rag.tool.CategoryTool;
import com.example.rag.tool.OrderTool;
import com.example.rag.tool.StockTool;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

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

    private final QueryRewriteService queryRewriteService;

    private final RecommendedQuestionService recommendedQuestionService;

    private final IntentRouter intentRouter;

    private final OrderTool orderTool;

    private final StockTool stockTool;

    private final CategoryTool categoryTool;

    private final MemoryService memoryService;

    private final MemoryProperties memoryProperties;

    private final ObjectMapper objectMapper;

    private final LlmCallRecorder llmCallRecorder;

    public ChatService(WebClient webClient,
                       LlmProperties llmProperties,
                       RetrievalService retrievalService,
                       QueryRewriteService queryRewriteService,
                       RecommendedQuestionService recommendedQuestionService,
                       IntentRouter intentRouter,
                       OrderTool orderTool,
                       StockTool stockTool,
                       CategoryTool categoryTool,
                       MemoryService memoryService,
                       MemoryProperties memoryProperties,
                       ObjectMapper objectMapper,
                       LlmCallRecorder llmCallRecorder) {
        this.webClient = webClient;
        this.llmProperties = llmProperties;
        this.retrievalService = retrievalService;
        this.queryRewriteService = queryRewriteService;
        this.recommendedQuestionService = recommendedQuestionService;
        this.intentRouter = intentRouter;
        this.orderTool = orderTool;
        this.stockTool = stockTool;
        this.categoryTool = categoryTool;
        this.memoryService = memoryService;
        this.memoryProperties = memoryProperties;
        this.objectMapper = objectMapper;
        this.llmCallRecorder = llmCallRecorder;
    }

    public void streamChat(String question, SseEmitter emitter, boolean skipRetrieval,
                           ChatSession session, String traceId,
                           boolean enableRecommend, Long knowledgeBaseId) {
        long start = System.currentTimeMillis();

        // 新会话首次请求时下发 sessionId，前端持久化后后续请求带回
        if (!sendSession(emitter, session, traceId)) {
            return;
        }
        // 用户问题先落库，后续链路失败也可追溯
        memoryService.appendUserMessage(session, question);

        // —— 意图识别：三路门控（Pattern → Embedding → LLM），决定走哪条路由 ——
        IntentResult intentResult = intentRouter.classify(question);
        Intent intent = intentResult.intent();
        log.info("[traceId={}] 意图识别结果: {} (confidence={}, matchedBy={})",
                traceId, intent, String.format("%.2f", intentResult.confidence()), intentResult.matchedBy());
        switch (intent) {
            case ORDER_QUERY -> {
                String orderNo = intentResult.entity("orderNo");
                String result = orderNo == null ? "请提供订单编号，格式如 20241027001" : orderTool.queryOrder(orderNo);
                memoryService.appendAssistantMessage(session,
                        sendToolResult(emitter, traceId, result, "order"), null);
                return;
            }
            case STOCK_QUERY -> {
                String pidStr = intentResult.entity("productId");
                Long productId = pidStr != null ? Long.parseLong(pidStr) : null;
                memoryService.appendAssistantMessage(session,
                        sendToolResult(emitter, traceId, stockTool.queryStock(productId), "stock"), null);
                return;
            }
            case CATEGORY_QUERY -> {
                memoryService.appendAssistantMessage(session,
                        sendToolResult(emitter, traceId, categoryTool.listCategories(), "category"), null);
                return;
            }
            case REFUSE -> {
                refuse(emitter, traceId);
                memoryService.appendAssistantMessage(session, REFUSAL, null);
                return;
            }
            case CHITCHAT -> {
                // 闲聊：跳过检索，直接 LLM（skipRetrieval=true）
                skipRetrieval = true;
            }
            case RAG_QUERY -> {
                // 正常走下面的检索链路，什么都不做
            }
        }

        List<Chunk> retrieved = List.of();
        RetrievalOutcome outcome = null;

        // 上下文由服务端按预算装配：摘要 + 摘要边界之后的最近消息，前端不再决定注入多少历史
        MemoryContext memory = memoryService.loadContext(session);
        List<Map<String, String>> recentHistory = toMessages(memory.recentMessages());
        String effectiveSummary = memory.summary();
        if (memory.droppedMessages() > 0) {
            log.info("[traceId={}] 上下文裁剪：丢弃 {} 条历史，实际注入 {} 字",
                    traceId, memory.droppedMessages(), memory.contextChars());
        }

        // 检索用改写后的问题（补全追问指代），用户消息和 messages 仍用原问题
        String queryForRetrieval = question;
        if (!skipRetrieval) {
            long tRewrite = System.currentTimeMillis();
            queryForRetrieval = queryRewriteService.rewrite(question, recentHistory);
            log.info("[traceId={}] 阶段=改写 耗时={}ms 「{}」→「{}」",
                    traceId, System.currentTimeMillis() - tRewrite, question, queryForRetrieval);
        }

        if (skipRetrieval) {
            log.info("[traceId={}] 跳过检索，按普通对话处理", traceId);
        } else {
            try {
                // 检索链路：向量化 → 候选召回 → Rerank → 截 topK → 证据闸门
                outcome = retrievalService.retrieve(queryForRetrieval, traceId, knowledgeBaseId);
            } catch (Exception e) {
                // 请求中途才暴露的故障（DB 断开、embedding 失败）：通知前端后降级为裸 LLM
                log.error("[traceId={}] 检索链路失败，降级为普通对话", traceId, e);
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
                log.info("[traceId={}] 证据闸门拦截，固定拒答：top1={}",
                        traceId, String.format("%.3f", outcome.getTop1Score()));
                refuse(emitter, traceId);
                return;
            }
        }

        if (!retrieved.isEmpty()) {
            log.info("[traceId={}] 召回 {} 个分块，相似度：{}",
                    traceId, retrieved.size(),
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

        final List<Chunk> retrievedFinal = retrieved;

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
                // 要求服务端在流末尾补一个 usage chunk，否则 token 消耗无从统计
                "stream_options", Map.of("include_usage", true),
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
        long genStart = System.currentTimeMillis();
        AtomicBoolean firstTokenLogged = new AtomicBoolean(false);
        // 累积答案正文，生成结束后用于追问推荐
        StringBuilder answerBuf = new StringBuilder();
        // 累积 usage，done 事件里带给前端
        AtomicReference<Map<String, Object>> usageRef = new AtomicReference<>();
        Disposable subscription = flux.subscribe(
                chunk -> {
                    if (firstTokenLogged.compareAndSet(false, true)) {
                        log.info("[traceId={}] 阶段=首token 耗时={}ms", traceId, System.currentTimeMillis() - genStart);
                    }
                    // usage 可能出现在独立的末尾 chunk，也可能与最后一个 delta 同 chunk，这里统一提取
                    if (chunk != null && chunk.contains("\"usage\"")) {
                        Map<String, Object> parsedUsage = parseUsage(chunk);
                        if (!parsedUsage.isEmpty()) {
                            usageRef.set(parsedUsage);
                        }
                    }
                    StreamDelta delta = extractStreamDelta(chunk);
                    if (delta == null) {
                        // [DONE] 或 usage-only chunk：不透传 content
                        return;
                    }
                    if (delta.content != null) {
                        answerBuf.append(delta.content);
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("content")
                                    .data(Map.of("content", delta.content), MediaType.APPLICATION_JSON));
                        } catch (IOException e) {
                            log.warn("[traceId={}] content 事件发送失败，客户端可能已断开", traceId, e);
                            sendError(emitter, traceId, e.getMessage(), "sse-send");
                            Disposable current = subscriptionRef.get();
                            if (current != null) { current.dispose(); }
                            emitter.completeWithError(e);
                        }
                    }
                    if (delta.reasoning != null) {
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("reasoning")
                                    .data(Map.of("reasoning", delta.reasoning), MediaType.APPLICATION_JSON));
                        } catch (IOException e) {
                            log.warn("[traceId={}] reasoning 事件发送失败", traceId, e);
                            sendError(emitter, traceId, e.getMessage(), "sse-send");
                            Disposable current = subscriptionRef.get();
                            if (current != null) { current.dispose(); }
                            emitter.completeWithError(e);
                        }
                    }
                },
                e -> {
                    log.error("[traceId={}] 上游 LLM 流式调用失败", traceId, e);
                    // 失败也要记台账，否则成本度量会低估真实消耗
                    llmCallRecorder.record(session, traceId, "chat", llmProperties.getModelName(),
                            usageRef.get(), System.currentTimeMillis() - genStart, false, messages);
                    sendError(emitter, traceId, e.getMessage(), "llm-stream");
                    emitter.completeWithError(e);
                },
                () -> {
                    completedNormally.set(true);
                    long genLatency = System.currentTimeMillis() - genStart;
                    log.info("[traceId={}] 阶段=生成 耗时={}ms 总耗时={}ms",
                            traceId, genLatency, System.currentTimeMillis() - start);
                    // 台账：模型、token、耗时、上下文快照，供成本分析与微调语料还原
                    llmCallRecorder.record(session, traceId, "chat", llmProperties.getModelName(),
                            usageRef.get(), genLatency, true, messages);
                    // 后续工作含阻塞调用（推荐服务内部 block、JDBC 写库），
                    // 回调运行在 reactor-http-nio 事件循环线程，切到弹性线程池避免阻塞 IO 线程
                    Schedulers.boundedElastic().schedule(() -> {
                        // done 事件：带 usage 信息
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("done")
                                    .data(Map.of("usage", usageRef.get() != null ? usageRef.get() : Map.of()),
                                            MediaType.APPLICATION_JSON));
                        } catch (Exception ex) {
                            log.warn("[traceId={}] done 事件发送失败", traceId, ex);
                        }
                        // 回答结束后生成追问推荐，事件下发后再关闭连接
                        if (enableRecommend && answerBuf.length() > 0) {
                            try {
                                List<String> questions = recommendedQuestionService.recommend(
                                        question, answerBuf.toString());
                                if (!questions.isEmpty()) {
                                    emitter.send(SseEmitter.event()
                                            .name("recommend")
                                            .data(Map.of("questions", questions), MediaType.APPLICATION_JSON));
                                }
                            } catch (Exception ex) {
                                log.warn("[traceId={}] 推荐问题下发失败", traceId, ex);
                            }
                        }
                        // 回答落库，再按阈值触发摘要压缩；记忆写入失败不影响本轮回复
                        try {
                            String answer = answerBuf.toString();
                            if (!answer.isEmpty()) {
                                memoryService.appendAssistantMessage(session, answer, toSourcesJson(retrievedFinal));
                            }
                            compactIfNeeded(session, traceId);
                        } catch (Exception ex) {
                            log.warn("[traceId={}] 记忆写入失败", traceId, ex);
                        }
                        emitter.complete();
                    });
                }
        );
        subscriptionRef.set(subscription);

        emitter.onCompletion(() -> {
            if (!completedNormally.get()) {
                log.info("[traceId={}] 客户端断开 SSE，取消上游订阅", traceId);
            }
            subscription.dispose();
        });
        emitter.onTimeout(() -> {
            log.warn("[traceId={}] SSE 连接超时，取消上游订阅", traceId);
            subscription.dispose();
        });
        emitter.onError(e -> {
            log.debug("[traceId={}] SSE 异步错误回调：{}", traceId, e.toString());
            subscription.dispose();
        });
    }

    /** 闸门拦截：发 event: content + event: done，业务层面的"正常拒答"，不是 error。 */
    private void refuse(SseEmitter emitter, String traceId) {
        try {
            emitter.send(SseEmitter.event()
                    .name("content")
                    .data(Map.of("content", REFUSAL), MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(Map.of("usage", Map.of()), MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException e) {
            log.warn("[traceId={}] 拒答消息发送失败，客户端可能已断开", traceId, e);
            sendError(emitter, traceId, e.getMessage(), "refuse");
            emitter.completeWithError(e);
        }
    }

    /**
     * Tool 查询结果直接发给前端，不经过 LLM。格式和正常对话一致：content + done。
     * 返回实际下发的正文，供记忆落库。
     */
    private String sendToolResult(SseEmitter emitter, String traceId, String result, String toolName) {
        String content = "【" + toolName + "查询结果】\n" + result;
        try {
            emitter.send(SseEmitter.event()
                    .name("content")
                    .data(Map.of("content", content), MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(Map.of("usage", Map.of(), "tool", toolName), MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException e) {
            log.warn("[traceId={}] Tool 结果发送失败 tool={}", traceId, toolName, e);
            sendError(emitter, traceId, e.getMessage(), "tool-" + toolName);
            emitter.completeWithError(e);
        }
        return content;
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

    /** 首次会话把 sessionId 下发给前端；发送失败说明客户端已断开，直接结束本轮。 */
    private boolean sendSession(SseEmitter emitter, ChatSession session, String traceId) {
        try {
            emitter.send(SseEmitter.event()
                    .name("session")
                    .data(Map.of("sessionId", session.getSessionKey()), MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException e) {
            log.warn("[traceId={}] session 事件发送失败，客户端可能已断开", traceId, e);
            emitter.completeWithError(e);
            return false;
        }
    }

    /** 会话消息 -> LLM messages 结构，过滤空白内容。 */
    private List<Map<String, String>> toMessages(List<ChatMessage> messages) {
        List<Map<String, String>> result = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            if (message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            result.add(Map.of("role", message.getRole(), "content", message.getContent()));
        }
        return result;
    }

    /** 引用分块序列化为 JSON 供落库；失败返回 null，不影响回答。 */
    private String toSourcesJson(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(buildSources(chunks));
        } catch (JsonProcessingException e) {
            log.warn("来源序列化失败，跳过引用持久化", e);
            return null;
        }
    }

    /**
     * 未被摘要覆盖的消息超过阈值时压缩：早期消息交给 LLM 生成摘要并落库，
     * 最近 summaryKeepTurns 轮仍保留原文。任何失败都静默，原文继续可用。
     */
    private void compactIfNeeded(ChatSession session, String traceId) {
        long pending = memoryService.pendingMessageCount(session);
        int trigger = memoryProperties.getSummaryTriggerMessages();
        if (pending <= trigger) {
            return;
        }
        int keepCount = Math.max(memoryProperties.getSummaryKeepTurns() * 2, 0);
        List<ChatMessage> pendingMessages = memoryService.pendingMessages(session, (int) pending);
        int earlyCount = pendingMessages.size() - keepCount;
        if (earlyCount <= 0) {
            return;
        }
        List<ChatMessage> early = pendingMessages.subList(0, earlyCount);
        long tSummary = System.currentTimeMillis();
        String summary = summarize(session, early, session.getSummary(), traceId);
        if (summary == null || summary.isBlank()) {
            return;
        }
        long summaryLastId = early.get(early.size() - 1).getId();
        memoryService.saveSummary(session, summary, summaryLastId);
        log.info("[traceId={}] 阶段=摘要压缩 耗时={}ms 归档 {} 条，摘要 {} 字，边界消息 id={}",
                traceId, System.currentTimeMillis() - tSummary, early.size(), summary.length(), summaryLastId);
    }

    /** 调 LLM 合并生成摘要，失败返回 null。 */
    private String summarize(ChatSession session, List<ChatMessage> early, String existingSummary, String traceId) {
        List<Map<String, String>> summaryMessages = new ArrayList<>();
        summaryMessages.add(Map.of("role", "system", "content", SUMMARY_PROMPT));
        if (existingSummary != null && !existingSummary.isBlank()) {
            summaryMessages.add(Map.of("role", "system", "content",
                    "<conversation-summary>\n" + existingSummary + "\n</conversation-summary>"));
        }
        summaryMessages.addAll(toMessages(early));
        summaryMessages.add(Map.of("role", "user", "content", "请输出对话摘要。"));

        Map<String, Object> requestBody = Map.of(
                "model", llmProperties.getModelName(),
                "stream", false,
                "messages", summaryMessages
        );

        long tCall = System.currentTimeMillis();
        try {
            String response = webClient.post()
                    .uri(llmProperties.getBaseUrl() + "/chat/completions")
                    .header("Authorization", "Bearer " + llmProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            long latency = System.currentTimeMillis() - tCall;
            if (response == null) {
                llmCallRecorder.record(session, traceId, "summary", llmProperties.getModelName(),
                        null, latency, false, summaryMessages);
                return null;
            }
            llmCallRecorder.record(session, traceId, "summary", llmProperties.getModelName(),
                    parseUsage(response), latency, true, summaryMessages);
            // 非流式响应：{"choices":[{"message":{"content":"..."}}]}
            String content = extractContent(response);
            return content == null || content.isBlank() ? null : content;
        } catch (Exception e) {
            llmCallRecorder.record(session, traceId, "summary", llmProperties.getModelName(),
                    null, System.currentTimeMillis() - tCall, false, summaryMessages);
            log.warn("[traceId={}] 摘要生成失败，保留原文", traceId, e);
            return null;
        }
    }

    /** 从流式 chunk（{"choices":[{"delta":{"content":"xxx"}}]}) 提取 content / reasoning_content。 */
    private StreamDelta extractStreamDelta(String chunk) {
        if (chunk == null || chunk.isBlank() || "[DONE]".equals(chunk.trim())) {
            return null;
        }
        try {
            int deltaIdx = chunk.indexOf("\"delta\"");
            if (deltaIdx < 0) {
                return null;
            }
            int contentStart = chunk.indexOf("\"content\":\"", deltaIdx);
            String content = null;
            if (contentStart > 0) {
                content = extractJsonString(chunk, contentStart + "\"content\":\"".length());
            }
            int reasoningStart = chunk.indexOf("\"reasoning_content\":\"", deltaIdx);
            String reasoning = null;
            if (reasoningStart > 0) {
                reasoning = extractJsonString(chunk, reasoningStart + "\"reasoning_content\":\"".length());
            }
            if (content == null && reasoning == null) {
                return null;
            }
            return new StreamDelta(content, reasoning);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 JSON 字符串指定偏移处提取字符串值，处理转义。 */
    private String extractJsonString(String json, int start) {
        if (start >= json.length()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int i = start;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') {
                break;
            }
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append(next);
                }
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** 从 usage-only chunk 提取 usage 字段，done 事件里带给前端。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseUsage(String chunk) {
        try {
            int usageIdx = chunk.indexOf("\"usage\"");
            if (usageIdx < 0) return Map.of();
            int braceStart = chunk.indexOf('{', usageIdx);
            if (braceStart < 0) return Map.of();
            int depth = 0;
            int end = braceStart;
            for (; end < chunk.length(); end++) {
                char c = chunk.charAt(end);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) { end++; break; }
                }
            }
            String usageJson = chunk.substring(braceStart, end);
            Map<String, Object> result = new java.util.HashMap<>();
            // 手动提三个常用字段：prompt_tokens / completion_tokens / total_tokens
            for (String key : new String[]{"prompt_tokens", "completion_tokens", "total_tokens"}) {
                int keyIdx = usageJson.indexOf("\"" + key + "\"");
                if (keyIdx > 0) {
                    int colon = usageJson.indexOf(':', keyIdx);
                    if (colon > 0) {
                        int comma = usageJson.indexOf(',', colon);
                        int rbrace = usageJson.indexOf('}', colon);
                        int numEnd = (comma > 0 && comma < rbrace) ? comma : rbrace;
                        String numStr = usageJson.substring(colon + 1, numEnd).trim();
                        try { result.put(key, Integer.parseInt(numStr)); } catch (NumberFormatException ignored) {}
                    }
                }
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 向前端下发 error 事件（best-effort，发送失败不再递归报错）。 */
    private void sendError(SseEmitter emitter, String traceId, String message, String stage) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("error", message != null ? message : "未知错误",
                            "stage", stage != null ? stage : "unknown"),
                            MediaType.APPLICATION_JSON));
        } catch (IOException ignored) {
            // error 事件本身发不出就放弃，避免死循环
        }
    }

    /** 流式 chunk 解析结果：content 正文增量 + reasoning 推理增量（可能为空）。 */
    private record StreamDelta(String content, String reasoning) {}

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
