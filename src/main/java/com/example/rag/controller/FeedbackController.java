package com.example.rag.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.rag.repository.FeedbackRepository;

/**
 * 对话反馈：用户对回答点赞/踩，后端落库留待后续分析。
 */
@RestController
@RequestMapping("/feedback")
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    private final FeedbackRepository feedbackRepository;

    public FeedbackController(FeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
    }

    @PostMapping
    public Map<String, Object> submit(@RequestBody Map<String, String> body) {
        String question = body.get("question");
        String answer = body.get("answer");
        String feedback = body.get("feedback");

        if (question == null || answer == null
                || !"UP".equals(feedback) && !"DOWN".equals(feedback)) {
            return Map.of("success", false, "message", "参数不合法");
        }

        feedbackRepository.insert(question, answer, feedback);
        log.info("收到反馈 {}：问题前 20 字「{}」", feedback,
                question.length() > 20 ? question.substring(0, 20) + "…" : question);
        return Map.of("success", true);
    }
}
