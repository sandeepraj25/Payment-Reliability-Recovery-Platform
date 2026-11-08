package com.payguard.webhook;

import com.payguard.support.IntegrationTestBase;
import com.payguard.webhook.signature.WebhookSignature;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Module B against the real HTTP endpoint and real PostgreSQL.
 * Invariant under test: "invalid signatures never mutate state".
 */
class WebhookIngestionIT extends IntegrationTestBase {

    private static final String URL = "/api/webhooks/payguard-sim";

    private Response deliver(String body, String signature) {
        var spec = RestAssured.given().contentType(ContentType.JSON).body(body.getBytes(StandardCharsets.UTF_8));
        if (signature != null) {
            spec = spec.header(WebhookSignature.SIGNATURE_HEADER, signature);
        }
        return spec.when().post(URL);
    }

    @Test
    void validEventIsStoredBeforeAcknowledgement() {
        String body = eventJson("evt_A1", "payment.captured", "pay_1", 2, "499900", "CAPTURED");

        deliver(body, sign(body)).then().statusCode(200)
                .body("result", equalTo("ACCEPTED"))
                .body("eventId", equalTo("evt_A1"));

        // The 200 above was returned after COMMIT, so the row must be visible to a different connection now.
        var row = jdbc.sql("SELECT processing_status, gateway_payment_id, gateway_payment_version, raw_body, duplicate_count "
                        + "FROM webhook_events WHERE provider_event_id = 'evt_A1'")
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getInt(5)})
                .single();
        // The worker (Phase 2) may already be working on it - this payment is unknown to the merchant,
        // so it will be retried and eventually dead-lettered. Any of these proves it was stored.
        assertThat(row[0]).isIn("RECEIVED", "RETRY_SCHEDULED", "DEAD");
        // ...and the outbox row was committed in the same transaction:
        assertThat(count("SELECT count(*) FROM outbox_events WHERE purpose = 'INGEST'")).isEqualTo(1);
        assertThat(row[1]).isEqualTo("pay_1");
        assertThat(row[2]).isEqualTo(2L);
        assertThat(row[3]).isEqualTo(body);   // exact bytes kept for audit / re-verification
        assertThat(row[4]).isEqualTo(0);
    }

    @Test
    void duplicateDeliveryIsAcknowledgedButStoredOnce() {
        String body = eventJson("evt_D1", "payment.captured", "pay_1", 2, "499900", "CAPTURED");

        deliver(body, sign(body)).then().statusCode(200).body("result", equalTo("ACCEPTED"));
        deliver(body, sign(body)).then().statusCode(200).body("result", equalTo("DUPLICATE"));
        deliver(body, sign(body)).then().statusCode(200).body("result", equalTo("DUPLICATE"));

        assertThat(count("SELECT count(*) FROM webhook_events")).isEqualTo(1);
        assertThat(count("SELECT duplicate_count FROM webhook_events WHERE provider_event_id = 'evt_D1'")).isEqualTo(2);
    }

    @Test
    void invalidSignatureIsRejectedAndNeverStoredAsAnEvent() {
        String body = eventJson("evt_X1", "payment.captured", "pay_1", 2, "499900", "CAPTURED");
        String forged = WebhookSignature.sign(Instant.now().getEpochSecond(), body.getBytes(StandardCharsets.UTF_8),
                "attacker-guessed-secret-123".getBytes(StandardCharsets.UTF_8));

        deliver(body, forged).then().statusCode(401).body("code", equalTo("INVALID_SIGNATURE"));

        assertThat(count("SELECT count(*) FROM webhook_events")).isZero();
        assertThat(count("SELECT count(*) FROM webhook_rejections WHERE reason = 'INVALID_SIGNATURE'")).isEqualTo(1);
    }

    @Test
    void tamperedBodyIsRejected() {
        String original = eventJson("evt_T1", "payment.captured", "pay_1", 2, "100", "CAPTURED");
        String tampered = original.replace("\"amount_minor\":100", "\"amount_minor\":999999");

        deliver(tampered, sign(original)).then().statusCode(401).body("code", equalTo("INVALID_SIGNATURE"));
        assertThat(count("SELECT count(*) FROM webhook_events")).isZero();
    }

    @Test
    void missingSignatureIsRejected() {
        String body = eventJson("evt_M1", "payment.captured", "pay_1", 2, "100", "CAPTURED");
        deliver(body, null).then().statusCode(401).body("code", equalTo("MISSING_SIGNATURE"));
        assertThat(count("SELECT count(*) FROM webhook_events")).isZero();
    }

    @Test
    void replayOfAnOldGenuineRequestIsRejected() {
        String body = eventJson("evt_R1", "payment.captured", "pay_1", 2, "100", "CAPTURED");
        String tenMinutesOld = WebhookSignature.sign(Instant.now().getEpochSecond() - 600,
                body.getBytes(StandardCharsets.UTF_8), WEBHOOK_SECRET);

        deliver(body, tenMinutesOld).then().statusCode(401).body("code", equalTo("TIMESTAMP_OUT_OF_TOLERANCE"));
        assertThat(count("SELECT count(*) FROM webhook_events")).isZero();
    }

    /**
     * The attack that motivates the separate webhook_rejections table: an attacker sends a forged
     * "evt_123" FIRST, hoping the real evt_123 is later discarded as a duplicate. It must not work.
     */
    @Test
    void forgedEventCannotSquatOnARealEventId() {
        String fake = eventJson("evt_REAL", "payment.failed", "pay_1", 2, "100", "FAILED");
        deliver(fake, "t=" + Instant.now().getEpochSecond() + ",v1=" + "a".repeat(64)).then().statusCode(401);

        String real = eventJson("evt_REAL", "payment.captured", "pay_1", 2, "100", "CAPTURED");
        deliver(real, sign(real)).then().statusCode(200).body("result", equalTo("ACCEPTED"));

        assertThat(jdbc.sql("SELECT event_type FROM webhook_events WHERE provider_event_id = 'evt_REAL'")
                .query(String.class).single()).isEqualTo("payment.captured");
    }

    @Test
    void signedButMalformedPayloadIsRejectedWith400() {
        String notJson = "this is not json";
        deliver(notJson, sign(notJson)).then().statusCode(400).body("code", equalTo("MALFORMED_PAYLOAD"));

        String fractional = eventJson("evt_F1", "payment.captured", "pay_1", 2, "4999.50", "CAPTURED");
        deliver(fractional, sign(fractional)).then().statusCode(400).body("code", equalTo("MALFORMED_PAYLOAD"));

        assertThat(count("SELECT count(*) FROM webhook_events")).isZero();
        assertThat(count("SELECT count(*) FROM webhook_rejections WHERE reason = 'MALFORMED_PAYLOAD'")).isEqualTo(2);
    }

    @Test
    void unknownEventTypeIsStoredAsIgnored() {
        String body = "{\"id\":\"evt_U1\",\"type\":\"account.updated\",\"created_at\":\"2026-11-08T10:00:00Z\"}";
        deliver(body, sign(body)).then().statusCode(200).body("result", equalTo("IGNORED"));
        assertThat(jdbc.sql("SELECT processing_status FROM webhook_events WHERE provider_event_id = 'evt_U1'")
                .query(String.class).single()).isEqualTo("IGNORED");
    }

    @Test
    void eventIdHeaderMustMatchBody() {
        String body = eventJson("evt_H1", "payment.captured", "pay_1", 2, "100", "CAPTURED");
        RestAssured.given().contentType(ContentType.JSON).body(body.getBytes(StandardCharsets.UTF_8))
                .header(WebhookSignature.SIGNATURE_HEADER, sign(body))
                .header(WebhookSignature.EVENT_ID_HEADER, "evt_OTHER")
                .when().post(URL).then().statusCode(400);
        assertThat(count("SELECT count(*) FROM webhook_events")).isZero();
    }

    @Test
    void unknownProviderIs404AndStoresNothing() {
        String body = eventJson("evt_P1", "payment.captured", "pay_1", 2, "100", "CAPTURED");
        RestAssured.given().contentType(ContentType.JSON).body(body)
                .header(WebhookSignature.SIGNATURE_HEADER, sign(body))
                .when().post("/api/webhooks/not-a-provider").then().statusCode(404);
        assertThat(count("SELECT count(*) FROM webhook_events")).isZero();
        assertThat(count("SELECT count(*) FROM webhook_rejections")).isZero();
    }

    @Test
    void operatorApiRequiresAuthentication() {
        RestAssured.given().when().get("/api/webhook-events").then().statusCode(401);
        admin().when().get("/api/webhook-events").then().statusCode(200);
    }
}
