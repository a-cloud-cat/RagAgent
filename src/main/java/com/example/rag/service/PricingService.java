package com.example.rag.service;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import org.springframework.stereotype.Service;

import com.example.rag.config.PricingProperties;

/**
 * 峰谷时段判定：高峰为北京时间周一至周五 9:00-12:00、14:00-18:00，其余时段闲时半价。
 * 法定节假日不参与判断，周末按闲时处理。
 */
@Service
public class PricingService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final LocalTime MORNING_START = LocalTime.of(9, 0);

    private static final LocalTime MORNING_END = LocalTime.of(12, 0);

    private static final LocalTime AFTERNOON_START = LocalTime.of(14, 0);

    private static final LocalTime AFTERNOON_END = LocalTime.of(18, 0);

    private final PricingProperties properties;

    public PricingService(PricingProperties properties) {
        this.properties = properties;
    }

    public enum Period {
        PEAK, OFF_PEAK
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public Period currentPeriod() {
        return isPeak(LocalDateTime.now(ZONE)) ? Period.PEAK : Period.OFF_PEAK;
    }

    /** 当前时段应乘的价格倍率：高峰 1.0，闲时配置的折扣。 */
    public double currentRate() {
        return currentPeriod() == Period.PEAK ? 1.0 : properties.getOffPeakRatio();
    }

    boolean isPeak(LocalDateTime time) {
        DayOfWeek day = time.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime t = time.toLocalTime();
        boolean morning = !t.isBefore(MORNING_START) && t.isBefore(MORNING_END);
        boolean afternoon = !t.isBefore(AFTERNOON_START) && t.isBefore(AFTERNOON_END);
        return morning || afternoon;
    }
}
