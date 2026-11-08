package com.payguard.webhook.signature;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.payguard.webhook.signature.WebhookSignature.Outcome.*;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    private static final byte[] SECRET = "secret-one-0123456789".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER = "secret-two-0123456789".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BODY = "{\"id\":\"evt_1\",\"amount_minor\":499900}".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    private static WebhookSignature.Outcome verify(String header, byte[] body, List<byte[]> secrets) {
        return WebhookSignature.verify(header, body, secrets, NOW, TOLERANCE);
    }

    @Test
    void validSignatureIsAccepted() {
        String header = WebhookSignature.sign(NOW.getEpochSecond(), BODY, SECRET);
        assertThat(header).matches("t=\\d+,v1=[0-9a-f]{64}");
        assertThat(verify(header, BODY, List.of(SECRET))).isEqualTo(VALID);
    }

    @Test
    void changingOneByteOfTheBodyBreaksTheSignature() {
        String header = WebhookSignature.sign(NOW.getEpochSecond(), BODY, SECRET);
        byte[] tampered = "{\"id\":\"evt_1\",\"amount_minor\":499901}".getBytes(StandardCharsets.UTF_8);
        assertThat(verify(header, tampered, List.of(SECRET))).isEqualTo(INVALID_SIGNATURE);
    }

    @Test
    void whitespaceChangesAlsoBreakTheSignature() {
        // Proves why we verify RAW bytes: re-serialising JSON (even "equivalent" JSON) would fail.
        String header = WebhookSignature.sign(NOW.getEpochSecond(), BODY, SECRET);
        byte[] reformatted = "{\"id\": \"evt_1\", \"amount_minor\": 499900}".getBytes(StandardCharsets.UTF_8);
        assertThat(verify(header, reformatted, List.of(SECRET))).isEqualTo(INVALID_SIGNATURE);
    }

    @Test
    void wrongSecretIsRejected() {
        String header = WebhookSignature.sign(NOW.getEpochSecond(), BODY, OTHER);
        assertThat(verify(header, BODY, List.of(SECRET))).isEqualTo(INVALID_SIGNATURE);
    }

    @Test
    void changingTheTimestampBreaksTheSignature() {
        // An attacker replays an old request but rewrites t= to look fresh.
        String old = WebhookSignature.sign(NOW.getEpochSecond() - 3600, BODY, SECRET);
        String forged = old.replaceFirst("t=\\d+", "t=" + NOW.getEpochSecond());
        assertThat(verify(forged, BODY, List.of(SECRET))).isEqualTo(INVALID_SIGNATURE);
    }

    @Test
    void genuineButOldRequestIsReportedAsReplay() {
        String header = WebhookSignature.sign(NOW.getEpochSecond() - 301, BODY, SECRET);
        assertThat(verify(header, BODY, List.of(SECRET))).isEqualTo(TIMESTAMP_OUT_OF_TOLERANCE);
    }

    @Test
    void timestampExactlyAtToleranceIsStillValid() {
        String header = WebhookSignature.sign(NOW.getEpochSecond() - 300, BODY, SECRET);
        assertThat(verify(header, BODY, List.of(SECRET))).isEqualTo(VALID);
    }

    @Test
    void timestampFromTheFutureIsRejected() {
        String header = WebhookSignature.sign(NOW.getEpochSecond() + 301, BODY, SECRET);
        assertThat(verify(header, BODY, List.of(SECRET))).isEqualTo(TIMESTAMP_OUT_OF_TOLERANCE);
    }

    @Test
    void missingAndMalformedHeaders() {
        assertThat(verify(null, BODY, List.of(SECRET))).isEqualTo(MISSING_SIGNATURE);
        assertThat(verify("  ", BODY, List.of(SECRET))).isEqualTo(MISSING_SIGNATURE);
        assertThat(verify("garbage", BODY, List.of(SECRET))).isEqualTo(MALFORMED_SIGNATURE);
        assertThat(verify("t=abc,v1=00", BODY, List.of(SECRET))).isEqualTo(MALFORMED_SIGNATURE);
        assertThat(verify("v1=00", BODY, List.of(SECRET))).isEqualTo(MALFORMED_SIGNATURE);
        assertThat(verify("t=123", BODY, List.of(SECRET))).isEqualTo(MALFORMED_SIGNATURE);
        assertThat(verify("t=1,t=2,v1=00", BODY, List.of(SECRET))).isEqualTo(MALFORMED_SIGNATURE);
    }

    @Test
    void secretRotationAcceptsOldAndNewSecret() {
        String signedWithOld = WebhookSignature.sign(NOW.getEpochSecond(), BODY, OTHER);
        String signedWithNew = WebhookSignature.sign(NOW.getEpochSecond(), BODY, SECRET);
        assertThat(verify(signedWithOld, BODY, List.of(SECRET, OTHER))).isEqualTo(VALID);
        assertThat(verify(signedWithNew, BODY, List.of(SECRET, OTHER))).isEqualTo(VALID);
    }

    @Test
    void oneValidV1AmongSeveralIsEnough() {
        String good = WebhookSignature.sign(NOW.getEpochSecond(), BODY, SECRET);
        String header = "t=" + NOW.getEpochSecond() + ",v1=" + "0".repeat(64) + "," + good.substring(good.indexOf("v1="));
        assertThat(verify(header, BODY, List.of(SECRET))).isEqualTo(VALID);
    }

    @Test
    void unknownSchemesAreIgnored() {
        String header = WebhookSignature.sign(NOW.getEpochSecond(), BODY, SECRET) + ",v2=future";
        assertThat(verify(header, BODY, List.of(SECRET))).isEqualTo(VALID);
    }
}
