package com.example.rag.service;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.rag.config.PricingProperties;

class PricingServiceTest {

    private PricingService service;

    @BeforeEach
    void setUp() {
        service = new PricingService(new PricingProperties());
    }

    @Test
    void 工作日高峰边界为九点到十二点与十四点到十八点() {
        assertFalse(service.isPeak(at(8, 59)));
        assertTrue(service.isPeak(at(9, 0)));
        assertTrue(service.isPeak(at(11, 59)));
        assertFalse(service.isPeak(at(12, 0)));
        assertFalse(service.isPeak(at(13, 59)));
        assertTrue(service.isPeak(at(14, 0)));
        assertTrue(service.isPeak(at(17, 59)));
        assertFalse(service.isPeak(at(18, 0)));
    }

    @Test
    void 周末全天按闲时处理() {
        assertFalse(service.isPeak(LocalDateTime.of(2026, 10, 31, 10, 0)));
        assertFalse(service.isPeak(LocalDateTime.of(2026, 11, 1, 15, 0)));
    }

    @Test
    void 默认不启用计费以免本地模型按云端价格计算() {
        assertFalse(service.isEnabled());
    }

    /** 2026-10-26 是周一 */
    private LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(2026, 10, 26, hour, minute);
    }
}
