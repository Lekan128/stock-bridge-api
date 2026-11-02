package com.procurepal_services.stock_bridge_api.expected;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WhatsAppNumbersTest {

    @Test
    void nigerianNumbersInEveryWayPeopleTypeThemBecomeOneThing() {
        assertThat(WhatsAppNumbers.normalize("0803 123 4567")).isEqualTo("2348031234567");
        assertThat(WhatsAppNumbers.normalize("08031234567")).isEqualTo("2348031234567");
        assertThat(WhatsAppNumbers.normalize("+234 803 123 4567")).isEqualTo("2348031234567");
        assertThat(WhatsAppNumbers.normalize("234-803-123-4567")).isEqualTo("2348031234567");
        assertThat(WhatsAppNumbers.normalize("00234 803 123 4567")).isEqualTo("2348031234567");
        assertThat(WhatsAppNumbers.normalize("803 123 4567")).isEqualTo("2348031234567");
        assertThat(WhatsAppNumbers.normalize("(0803) 123-4567")).isEqualTo("2348031234567");
    }

    @Test
    void anotherCountryIsKeptOnlyWhenItWasTypedWithAPlus() {
        assertThat(WhatsAppNumbers.normalize("+44 7700 900123")).isEqualTo("447700900123");
        assertThat(WhatsAppNumbers.normalize("447700900123")).isNull();
    }

    @Test
    void anythingThatCannotBeReadGivesNullSoTheButtonIsLeftOut() {
        assertThat(WhatsAppNumbers.normalize(null)).isNull();
        assertThat(WhatsAppNumbers.normalize("")).isNull();
        assertThat(WhatsAppNumbers.normalize("call the shop")).isNull();
        assertThat(WhatsAppNumbers.normalize("0803 123")).isNull();
        assertThat(WhatsAppNumbers.normalize("0803 123 4567 89")).isNull();
    }
}
