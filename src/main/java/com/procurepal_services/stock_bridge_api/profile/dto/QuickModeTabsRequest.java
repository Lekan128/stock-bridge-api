package com.procurepal_services.stock_bridge_api.profile.dto;

import java.util.List;

/**
 * {@code PUT /api/me/quick-mode} (owner, 2026-11-01): the buttons this person wants in quick mode - one to four of
 * {@code sell, in, out, move, count}, in any order (quick mode shows them in its own). Absent or empty: back to the
 * app's own choice.
 */
public record QuickModeTabsRequest(List<String> tabs) {
}
