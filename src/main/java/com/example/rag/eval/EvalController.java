package com.example.rag.eval;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索评测 HTTP 入口：触发一次离线评测并返回 Hit@K 报告。
 */
@RestController
public class EvalController {

    private final EvalService evalService;

    public EvalController(EvalService evalService) {
        this.evalService = evalService;
    }

    /**
     * 跑一遍检索评测。topK 等参数取当前应用配置，结果含每题召回明细。
     */
    @PostMapping("/eval/retrieval")
    public EvalReport runRetrievalEval() {
        return evalService.run();
    }
}
