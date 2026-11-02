package com.example.rag.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.rag.repository.FeedbackRepository;

class FeedbackControllerTest {

    private FeedbackRepository feedbackRepository;
    private FeedbackController controller;

    @BeforeEach
    void setUp() {
        feedbackRepository = mock(FeedbackRepository.class);
        controller = new FeedbackController(feedbackRepository);
    }

    @Test
    void 合法UP反馈时落库并返回成功() {
        Map<String, Object> result = controller.submit(Map.of(
                "question", "问题", "answer", "回答", "feedback", "UP"));

        assertTrue((Boolean) result.get("success"));
        verify(feedbackRepository).insert("问题", "回答", "UP");
    }

    @Test
    void 合法DOWN反馈时落库并返回成功() {
        Map<String, Object> result = controller.submit(Map.of(
                "question", "问题", "answer", "回答", "feedback", "DOWN"));

        assertTrue((Boolean) result.get("success"));
        verify(feedbackRepository).insert("问题", "回答", "DOWN");
    }

    @Test
    void feedback取值非法时拒绝并不落库() {
        Map<String, Object> result = controller.submit(Map.of(
                "question", "问题", "answer", "回答", "feedback", "BAD"));

        assertFalse((Boolean) result.get("success"));
        assertEquals("参数不合法", result.get("message"));
        verify(feedbackRepository, never()).insert(anyString(), anyString(), anyString());
    }

    @Test
    void 缺少question时拒绝并不落库() {
        Map<String, Object> result = controller.submit(Map.of(
                "answer", "回答", "feedback", "UP"));

        assertFalse((Boolean) result.get("success"));
        verify(feedbackRepository, never()).insert(anyString(), anyString(), anyString());
    }
}
