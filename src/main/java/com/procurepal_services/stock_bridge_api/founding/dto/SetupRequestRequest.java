package com.procurepal_services.stock_bridge_api.founding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The landing page's two fields (conversion rule 4), plus where the request came from and a
 * honeypot.
 *
 * @param website a field people never see and bots fill in. A request with it set is answered as
 *                if accepted and then dropped.
 */
public record SetupRequestRequest(
        @NotBlank(message = "Enter your business name.") @Size(max = 120, message = "Keep the business name under 120 characters.") String businessName,
        @NotBlank(message = "Enter your WhatsApp number.") @Size(max = 24) String whatsapp,
        @Pattern(regexp = "landing|founding|pricing", message = "Unknown source.") String source,
        @Size(max = 200) String website) {
}
