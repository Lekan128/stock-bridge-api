package com.procurepal_services.stock_bridge_api.imports.dto;

import com.procurepal_services.stock_bridge_api.imports.CommitPreview;
import java.util.List;

/**
 * BULK_IMPORT_CONTRACT.md section 4's {@code CommitPreviewResponse} - the confirm screen, which
 * design 9.4 insists is prose and not a form to refill.
 *
 * <p>A thin re-shaping of {@link CommitPreview} rather than the handler's record going straight
 * onto the wire: the SPI type is free to grow fields the confirm screen has no business seeing,
 * and this one is pinned by the contract.
 */
public record CommitPreviewResponse(
        String headline, List<Line> lines, String confirmLabel, boolean blocked, String blockedReason) {

    public record Line(String key, String label, int count, String text) {
    }

    public static CommitPreviewResponse from(CommitPreview preview) {
        return new CommitPreviewResponse(
                preview.headline(),
                preview.lines().stream()
                        .map(line -> new Line(line.key(), line.label(), line.count(), line.text()))
                        .toList(),
                preview.confirmLabel(),
                preview.blocked(),
                preview.blockedReason());
    }
}
