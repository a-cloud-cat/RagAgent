package com.example.rag.service;

import java.util.List;
import java.util.Map;

/**
 * 查询改写：把多轮对话里的短追问（如"它具体指什么"）补全成独立完整的检索问题。
 * 改写只影响检索 query，用户看到的原问题和 LLM messages 里的 user 消息保持不变。
 */
public interface QueryRewriteService {

    /**
     * 基于对话历史改写当前问题为适合检索的完整问题。
     * 无历史或改写失败时回退原问题。
     */
    String rewrite(String question, List<Map<String, String>> history);
}
