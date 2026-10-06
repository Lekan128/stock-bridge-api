package com.procurepal_services.stock_bridge_api.founding;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Nigerian mobile numbers as people type them, into the one form WhatsApp links use:
 * {@code 0803 123 4567}, {@code 2348031234567} and {@code +234 803 123 4567} are all
 * {@code +2348031234567}. Anything that isn't a Nigerian mobile number is refused, so the team
 * never gets a lead it can't message.
 */
public final class WhatsAppNumbers {

    /** +234, then a mobile prefix (70x, 80x, 81x, 90x, 91x…), then 8 digits. */
    private static final Pattern NIGERIAN_MOBILE = Pattern.compile("^\\+234[789][01]\\d{8}$");

    private WhatsAppNumbers() {
    }

    public static Optional<String> normalise(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String digits = typed.replaceAll("[\\s().-]", "");
        if (digits.startsWith("+")) {
            digits = digits.substring(1);
        } else if (digits.startsWith("00")) {
            digits = digits.substring(2);
        }
        if (!digits.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        if (digits.length() == 11 && digits.startsWith("0")) {
            digits = "234" + digits.substring(1);
        }
        String e164 = "+" + digits;
        return NIGERIAN_MOBILE.matcher(e164).matches() ? Optional.of(e164) : Optional.empty();
    }
}
