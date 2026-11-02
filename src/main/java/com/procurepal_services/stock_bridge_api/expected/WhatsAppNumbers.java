package com.procurepal_services.stock_bridge_api.expected;

/**
 * Turns a phone number as someone typed it into the digits WhatsApp's {@code wa.me} link wants:
 * country code first, no plus, no spaces. A number that cannot be read this way gives null, and the
 * screen then leaves the WhatsApp button out instead of opening a broken chat.
 */
final class WhatsAppNumbers {

    private WhatsAppNumbers() {
    }

    /**
     * "0803 123 4567" and "+234 803 123 4567" and "803 123 4567" all become "2348031234567". A number
     * that already starts with another country code is kept as typed, if it is a plausible length.
     */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
        }
        if (digits.startsWith("234")) {
            return digits.length() == 13 ? digits : null;
        }
        if (digits.startsWith("0") && digits.length() == 11) {
            return "234" + digits.substring(1);
        }
        if (digits.length() == 10 && "789".indexOf(digits.charAt(0)) >= 0) {
            return "234" + digits;
        }
        // Someone else's country code, typed in full: trust it if it is a plausible length.
        if (raw.trim().startsWith("+") && digits.length() >= 10 && digits.length() <= 15) {
            return digits;
        }
        return null;
    }
}
