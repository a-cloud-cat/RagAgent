package com.example.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 峰谷计费配置。价格均为高峰时段单价，单位元/百万 tokens。
 */
@ConfigurationProperties(prefix = "rag.llm.pricing")
public class PricingProperties {

    /** 本地模型不产生云端费用，默认关闭 */
    private boolean enabled = false;

    private double peakCacheHitPrice = 0.30;

    private double peakCacheMissPrice = 9.0;

    private double peakOutputPrice = 27.0;

    /** 闲时倍率：闲时单价为高峰的一半 */
    private double offPeakRatio = 0.5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getPeakCacheHitPrice() {
        return peakCacheHitPrice;
    }

    public void setPeakCacheHitPrice(double peakCacheHitPrice) {
        this.peakCacheHitPrice = peakCacheHitPrice;
    }

    public double getPeakCacheMissPrice() {
        return peakCacheMissPrice;
    }

    public void setPeakCacheMissPrice(double peakCacheMissPrice) {
        this.peakCacheMissPrice = peakCacheMissPrice;
    }

    public double getPeakOutputPrice() {
        return peakOutputPrice;
    }

    public void setPeakOutputPrice(double peakOutputPrice) {
        this.peakOutputPrice = peakOutputPrice;
    }

    public double getOffPeakRatio() {
        return offPeakRatio;
    }

    public void setOffPeakRatio(double offPeakRatio) {
        this.offPeakRatio = offPeakRatio;
    }
}
