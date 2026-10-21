package com.procurepal_services.stock_bridge_api.selling.dto;

import java.util.List;
import java.util.UUID;

/** @param lineIds the lines to cancel; absent or empty cancels every line not already cancelled. */
public record CancelSaleRequest(List<UUID> lineIds) {
}
