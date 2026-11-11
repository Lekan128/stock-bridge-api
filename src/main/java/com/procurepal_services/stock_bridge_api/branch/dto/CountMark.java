package com.procurepal_services.stock_bridge_api.branch.dto;

import com.procurepal_services.stock_bridge_api.stock.dto.StockCountRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * An area's last count of one product (2026-11-10): when, and how it was typed ("1 bag + 2 kg") when the count sent
 * its lines. Every area, a branch's only one included, so the count list knows what's counted today anywhere.
 */
public record CountMark(UUID areaId, OffsetDateTime at, List<StockCountRequest.CountedLine> countedAs) {
}
