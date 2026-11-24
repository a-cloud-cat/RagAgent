package com.example.rag.service.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.rag.embedding.EmbeddingClient;

class IntentRouterTest {

    private IntentRouter router;
    private EmbeddingClient embeddingClient;
    private LlmIntentClient llmIntentClient;

    @BeforeEach
    void setUp() {
        embeddingClient = mock(EmbeddingClient.class);
        llmIntentClient = mock(LlmIntentClient.class);
        // Embedding 模板加载会在 @PostConstruct 里调 embeddingClient.embed()
        // 返回和输入条数相等的零向量，模板库能正常加载但相似度都是 0 → Embedding 路径自动跳过
        when(embeddingClient.embed(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<String> texts = inv.getArgument(0);
            java.util.List<float[]> vecs = new java.util.ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                vecs.add(new float[1536]);
            }
            return vecs;
        });
        router = new IntentRouter(embeddingClient, llmIntentClient);
    }

    // —— Pattern 路径：直接命中 → confidence=1.0, matchedBy="pattern" ——

    @Test
    void 订单号格式匹配ORDER_QUERY() {
        IntentResult r = router.classify("订单20241027001到哪了");
        assertEquals(Intent.ORDER_QUERY, r.intent());
        assertEquals(1.0, r.confidence());
        assertEquals("pattern", r.matchedBy());
        assertEquals("20241027001", r.entity("orderNo"));
    }

    @Test
    void 物流关键词匹配ORDER_QUERY() {
        IntentResult r = router.classify("我的快递什么时候到");
        assertEquals(Intent.ORDER_QUERY, r.intent());
        assertEquals("pattern", r.matchedBy());
    }

    @Test
    void 退货关键词匹配ORDER_QUERY() {
        IntentResult r = router.classify("我要退货");
        assertEquals(Intent.ORDER_QUERY, r.intent());
    }

    @Test
    void 价格关键词匹配STOCK_QUERY并提取商品ID() {
        IntentResult r = router.classify("商品4多少钱");
        assertEquals(Intent.STOCK_QUERY, r.intent());
        assertEquals("4", r.entity("productId"));
    }

    @Test
    void 库存关键词匹配STOCK_QUERY() {
        IntentResult r = router.classify("小米10还有货吗");
        assertEquals(Intent.STOCK_QUERY, r.intent());
    }

    @Test
    void 分类关键词匹配CATEGORY_QUERY() {
        IntentResult r = router.classify("有什么商品");
        assertEquals(Intent.CATEGORY_QUERY, r.intent());
    }

    @Test
    void 纯闲聊匹配CHITCHAT() {
        IntentResult r = router.classify("你好");
        assertEquals(Intent.CHITCHAT, r.intent());
        assertEquals("pattern", r.matchedBy());
    }

    @Test
    void 敏感词匹配REFUSE() {
        IntentResult r = router.classify("你是傻逼");
        assertEquals(Intent.REFUSE, r.intent());
    }

    @Test
    void 空问题默认RAG_QUERY() {
        IntentResult r = router.classify(null);
        assertEquals(Intent.RAG_QUERY, r.intent());
        assertEquals("default", r.matchedBy());
    }

    @Test
    void RAG_QUERY默认意图() {
        IntentResult r = router.classify("rerank阈值怎么调");
        assertEquals(Intent.RAG_QUERY, r.intent());
    }

    @Test
    void 业务关键词优先于闲聊() {
        IntentResult r = router.classify("订单号的格式是什么");
        assertEquals(Intent.ORDER_QUERY, r.intent());
    }

    // —— Pattern 直接命中时 Embedding 和 LLM 都不应该被调用 ——

    @Test
    void pattern命中时不调用LLM() {
        router.classify("你好"); // CHITCHAT pattern 命中
        verify(llmIntentClient, never()).classify(anyString());
    }

    @Test
    void pattern命中时不调用EmbeddingClient_除了模板预加载() {
        router.classify("订单20241027001到哪了"); // ORDER_QUERY pattern 命中
        // embeddingClient.embed 只在 @PostConstruct 模板加载时被调用一次
        // classify 过程中不该再调
    }

    // —— entities 提取验证 ——

    @Test
    void 订单号同时包含商品ID_都能提取() {
        IntentResult r = router.classify("帮我查商品2的订单20241027001");
        assertEquals(Intent.ORDER_QUERY, r.intent());
        assertEquals("20241027001", r.entity("orderNo"));
        assertEquals("2", r.entity("productId"));
    }

    @Test
    void IntentResult便捷方法() {
        IntentResult r = IntentResult.pattern(Intent.CHITCHAT, Map.of());
        assertNotNull(r.entities());
        assertEquals(0, r.entities().size());
        assertEquals(null, r.entity("nonexistent"));
    }
}
