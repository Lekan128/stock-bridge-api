package com.procurepal_services.stock_bridge_api.onboarding.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A file a shop sent with "Send us your list". */
public record ProductListFile(UUID id, String fileName, String contentType, int sizeBytes, OffsetDateTime uploadedAt) {
}
