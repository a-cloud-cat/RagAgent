package com.example.rag.service.intent;

/**
 * 用户请求意图枚举，决定路由目标。
 */
public enum Intent {

    /** 知识库查询，走 RAG 检索链路 */
    RAG_QUERY,

    /** 订单查询，走 OrderTool */
    ORDER_QUERY,

    /** 商品库存查询，走 StockTool */
    STOCK_QUERY,

    /** 商品分类查询，走 CategoryTool */
    CATEGORY_QUERY,

    /** 闲聊 / 普通对话，跳过检索直接 LLM */
    CHITCHAT,

    /** 拒答，返回固定措辞 */
    REFUSE
}
