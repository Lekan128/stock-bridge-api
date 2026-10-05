package com.procurepal_services.stock_bridge_api.founding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WhatsAppNumbersTest {

    @Test
    void theWaysPeopleTypeANigerianMobileAllBecomeOneNumber() {
        for (String typed : new String[] {"0803 123 4567", "08031234567", "2348031234567", "+234 803 123 4567", "+234-803-123-4567", "(0803) 123 4567", "002348031234567"}) {
            assertThat(WhatsAppNumbers.normalise(typed)).as(typed).contains("+2348031234567");
        }
        assertThat(WhatsAppNumbers.normalise("0708 765 4321")).contains("+2347087654321");
        assertThat(WhatsAppNumbers.normalise("0915 000 1111")).contains("+2349150001111");
    }

    @Test
    void anythingThatIsNotANigerianMobileIsRefused() {
        for (String typed : new String[] {"", "12345", "0803 123 456", "080312345678", "+44 7700 900123", "0603 123 4567", "0803abc4567", null}) {
            assertThat(WhatsAppNumbers.normalise(typed)).as(String.valueOf(typed)).isEmpty();
        }
    }
}
