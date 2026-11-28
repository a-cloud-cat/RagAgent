package com.example.rag.eval;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评测 HTTP 入口：检索评测与意图识别评测。
 */
@RestController
public class EvalController {

    private final EvalService evalService;

    private final IntentEvalService intentEvalService;

    public EvalController(EvalService evalService, IntentEvalService intentEvalService) {
        this.evalService = evalService;
        this.intentEvalService = intentEvalService;
    }

    /**
     * 跑一遍检索评测。topK 等参数取当前应用配置，结果含每题召回明细。
     */
    @PostMapping("/eval/retrieval")
    public EvalReport runRetrievalEval() {
        return evalService.run();
    }

    /**
     * 跑一遍意图识别评测，返回准确率、各类别 P/R/F1、Macro-F1 与三路命中分布。
     */
    @PostMapping("/eval/intent")
    public IntentEvalReport runIntentEval() {
        return intentEvalService.run();
    }
}
