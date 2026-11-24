package com.example.rag.service.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntentRouterTest {

    private IntentRouter router;

    @BeforeEach
    void setUp() {
        router = new IntentRouter();
    }

    // —— RAG_QUERY 默认意图 ——
    @Test
    void 未匹配到任何Pattern时默认RAG_QUERY() {
        assertEquals(Intent.RAG_QUERY, router.classify("rerank阈值怎么调"));
        assertEquals(Intent.RAG_QUERY, router.classify("embedding模型选哪个"));
    }

    // —— ORDER_QUERY ——
    @Test
    void 含订单号格式匹配ORDER_QUERY() {
        assertEquals(Intent.ORDER_QUERY, router.classify("订单20241027001到哪了"));
        assertEquals(Intent.ORDER_QUERY, router.classify("帮我查下20241027002"));
    }

    @Test
    void 物流快递关键词匹配ORDER_QUERY() {
        assertEquals(Intent.ORDER_QUERY, router.classify("我的快递什么时候到"));
        assertEquals(Intent.ORDER_QUERY, router.classify("物流状态查一下"));
    }

    @Test
    void 退货退款关键词匹配ORDER_QUERY() {
        assertEquals(Intent.ORDER_QUERY, router.classify("我要退货"));
        assertEquals(Intent.ORDER_QUERY, router.classify("退款流程怎么走"));
    }

    // —— STOCK_QUERY ——
    @Test
    void 库存价格关键词匹配STOCK_QUERY() {
        assertEquals(Intent.STOCK_QUERY, router.classify("小米10还有货吗"));
        assertEquals(Intent.STOCK_QUERY, router.classify("商品4多少钱"));
        assertEquals(Intent.STOCK_QUERY, router.classify("这个报价多少"));
    }

    // —— CATEGORY_QUERY ——
    @Test
    void 分类关键词匹配CATEGORY_QUERY() {
        assertEquals(Intent.CATEGORY_QUERY, router.classify("你们有什么商品"));
        assertEquals(Intent.CATEGORY_QUERY, router.classify("商品分类有哪些"));
    }

    // —— CHITCHAT ——
    @Test
    void 纯闲聊匹配CHITCHAT() {
        assertEquals(Intent.CHITCHAT, router.classify("你好"));
        assertEquals(Intent.CHITCHAT, router.classify("你是谁"));
        assertEquals(Intent.CHITCHAT, router.classify("现在几点了"));
    }

    @Test
    void 闲聊是最短匹配避免误杀() {
        // "你好" 是 CHITCHAT，但"订单号格式是什么"应该是 RAG_QUERY，不会被闲聊误杀
        assertEquals(Intent.CHITCHAT, router.classify("你好"));
    }

    // —— REFUSE ——
    @Test
    void 敏感词匹配REFUSE() {
        assertEquals(Intent.REFUSE, router.classify("你是傻逼"));
    }

    // —— 边界 ——
    @Test
    void 空问题默认RAG_QUERY() {
        assertEquals(Intent.RAG_QUERY, router.classify(null));
        assertEquals(Intent.RAG_QUERY, router.classify(""));
        assertEquals(Intent.RAG_QUERY, router.classify("   "));
    }

    @Test
    void 业务关键词优先于闲聊() {
        // "订单号的格式" 包含"订单"，应该是 ORDER_QUERY，不是 CHITCHAT
        assertEquals(Intent.ORDER_QUERY, router.classify("订单号的格式是什么"));
    }
}
