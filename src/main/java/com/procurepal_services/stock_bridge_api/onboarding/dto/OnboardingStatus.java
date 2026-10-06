package com.procurepal_services.stock_bridge_api.onboarding.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The shop's setup checklist (LANDING_PAGE_PLAN.md §4), as the server knows it: so it is right on
 * every phone, and ticks itself when the team loads the products. Whether the app is installed is
 * only known on the phone, so the app adds that item itself.
 *
 * @param products        active products ("Add your products" is done when there is one)
 * @param stockChanges    the shop's own stock changes ("Record your first delivery or count")
 * @param staff           staff accounts other than the owner and Procurepaddy support
 * @param listFiles       files sent with "Send us your list"
 * @param setupStatus     the shop's setup request status, absent when it has none
 * @param supportAccess   ON when Procurepaddy support has an active account in the shop, OFF when
 *                        the owner switched it off, absent when there is none
 * @param createdAt       when the shop signed up
 */
public record OnboardingStatus(
        int products,
        int stockChanges,
        int staff,
        int listFiles,
        List<ProductListFile> files,
        String setupStatus,
        String supportAccess,
        OffsetDateTime createdAt) {
}
