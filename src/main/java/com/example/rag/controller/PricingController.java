package com.example.rag.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.rag.config.LlmProperties;
import com.example.rag.config.PricingProperties;
import com.example.rag.service.PricingService;

/**
 * 计费口径查询：前端据此展示当前时段与单价，用于估算单次问答费用。
 */
@RestController
@RequestMapping("/pricing")
public class PricingController {

    private final PricingService pricingService;

    private final PricingProperties pricingProperties;

    private final LlmProperties llmProperties;

    public PricingController(PricingService pricingService,
                             PricingProperties pricingProperties,
                             LlmProperties llmProperties) {
        this.pricingService = pricingService;
        this.pricingProperties = pricingProperties;
        this.llmProperties = llmProperties;
    }

    @GetMapping
    public Map<String, Object> current() {
        if (!pricingService.isEnabled()) {
            return Map.of("enabled", false);
        }
        return Map.of(
                "enabled", true,
                "model", llmProperties.getModelName(),
                "period", pricingService.currentPeriod().name(),
                "rate", pricingService.currentRate(),
                "prices", Map.of(
                        "cacheHit", pricingProperties.getPeakCacheHitPrice(),
                        "cacheMiss", pricingProperties.getPeakCacheMissPrice(),
                        "output", pricingProperties.getPeakOutputPrice()));
    }
}
