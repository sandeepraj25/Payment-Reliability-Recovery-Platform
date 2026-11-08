package com.payguard.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payguard.domain.payment.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookPayloadParserTest {

    private final WebhookPayloadParser parser = new WebhookPayloadParser(new ObjectMapper());

    private static String event(String type, String amountLiteral) {
        return """
                {"id":"evt_1","type":"%s","created_at":"2026-11-08T10:00:00Z",\
                "data":{"payment":{"id":"pay_1","amount_minor":%s,"currency":"INR","status":"CAPTURED","version":2}}}"""
                .formatted(type, amountLiteral);
    }

    private WebhookPayloadParser.ParsedEvent parse(String json) {
        return parser.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesAKnownEvent() {
        var e = parse(event("payment.captured", "499900"));
        assertThat(e.eventId()).isEqualTo("evt_1");
        assertThat(e.known()).isTrue();
        assertThat(e.paymentId()).isEqualTo("pay_1");
        assertThat(e.paymentVersion()).isEqualTo(2L);
        assertThat(e.amountMinor()).isEqualTo(499900L);
        assertThat(e.paymentStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(e.createdAt()).isEqualTo(Instant.parse("2026-11-08T10:00:00Z"));
    }

    @Test
    void unknownTypeIsAcceptedButMarkedUnknown() {
        var e = parse("{\"id\":\"evt_9\",\"type\":\"account.updated\"}");
        assertThat(e.known()).isFalse();
        assertThat(e.paymentId()).isNull();
    }

    @Test
    void fractionalMoneyIsRejectedNotRounded() {
        assertThatThrownBy(() -> parse(event("payment.captured", "4999.50")))
                .isInstanceOf(WebhookPayloadParser.MalformedPayloadException.class)
                .hasMessageContaining("amount_minor");
    }

    @Test
    void moneyAsStringIsRejected() {
        assertThatThrownBy(() -> parse(event("payment.captured", "\"499900\"")))
                .isInstanceOf(WebhookPayloadParser.MalformedPayloadException.class);
    }

    @Test
    void negativeAndZeroMoneyAreRejected() {
        assertThatThrownBy(() -> parse(event("payment.captured", "-1")))
                .isInstanceOf(WebhookPayloadParser.MalformedPayloadException.class);
        assertThatThrownBy(() -> parse(event("payment.captured", "0")))
                .isInstanceOf(WebhookPayloadParser.MalformedPayloadException.class);
    }

    @Test
    void knownTypeWithoutPaymentIsRejected() {
        assertThatThrownBy(() -> parse("{\"id\":\"evt_1\",\"type\":\"payment.captured\"}"))
                .isInstanceOf(WebhookPayloadParser.MalformedPayloadException.class);
    }

    @Test
    void structuralProblemsAreRejected() {
        assertThatThrownBy(() -> parse("not json")).isInstanceOf(WebhookPayloadParser.MalformedPayloadException.class);
        assertThatThrownBy(() -> parse("[1,2]")).isInstanceOf(WebhookPayloadParser.MalformedPayloadException.class);
        assertThatThrownBy(() -> parse("{\"type\":\"payment.captured\"}")).isInstanceOf(WebhookPayloadParser.MalformedPayloadException.class);
        assertThatThrownBy(() -> parse("{\"id\":\"evt 1 with spaces\",\"type\":\"x\"}"))
                .isInstanceOf(WebhookPayloadParser.MalformedPayloadException.class);
        assertThatThrownBy(() -> parse("{\"id\":\"evt_1\",\"type\":\"x\",\"created_at\":\"yesterday\"}"))
                .isInstanceOf(WebhookPayloadParser.MalformedPayloadException.class);
        assertThatThrownBy(() -> parse(event("payment.captured", "100").replace("\"INR\"", "\"inr\"")))
                .isInstanceOf(WebhookPayloadParser.MalformedPayloadException.class);
        assertThatThrownBy(() -> parse(event("payment.captured", "100").replace("CAPTURED", "SETTLED")))
                .isInstanceOf(WebhookPayloadParser.MalformedPayloadException.class);
    }

    @Test
    void invalidUtf8IsRejected() {
        byte[] bad = {'{', '"', 'i', 'd', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'};
        assertThatThrownBy(() -> parser.parse(bad)).isInstanceOf(WebhookPayloadParser.MalformedPayloadException.class);
    }
}
