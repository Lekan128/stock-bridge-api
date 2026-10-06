package com.procurepal_services.stock_bridge_api.onboarding.dto;

import com.procurepal_services.stock_bridge_api.onboarding.ShopActivity;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One new shop in the team's first-week list.
 *
 * @param whatsapp      the number to message, +234 form; absent when the shop gave none
 * @param activated     products loaded and its own first stock change within 72 hours of sign-up
 *                      (plan §4, the north star)
 * @param habit         own stock changes on at least 5 of its first 7 days
 * @param messages      first-week messages sent: WELCOME, LOADED, DAY_3, DAY_7, each with when
 * @param supportAccess ON, OFF (switched off by the owner) or absent (never opened)
 */
public record FirstWeekShop(
        UUID clientId,
        String name,
        String companyId,
        String whatsapp,
        OffsetDateTime createdAt,
        UUID setupRequestId,
        String setupStatus,
        boolean founding,
        ShopActivity activity,
        boolean activated,
        boolean habit,
        Map<String, OffsetDateTime> messages,
        String supportAccess) {
}
