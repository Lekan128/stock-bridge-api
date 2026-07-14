package com.procurepal_services.stock_bridge_api.analytics.dto;

import java.math.BigDecimal;

/** period is the bucket start date as "YYYY-MM-DD" (week/month buckets still report their start date). */
public record MovementsOverTimePoint(String period, BigDecimal inValue, BigDecimal outValue, long inQuantity, long outQuantity) {
}
