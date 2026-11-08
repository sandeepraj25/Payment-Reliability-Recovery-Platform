package com.payguard.ops;

import com.payguard.support.IntegrationTestBase;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/** Dashboard APIs return real, database-backed numbers; roles and observability plumbing work. */
class OpsIT extends IntegrationTestBase {

    @Test
    void summaryHealthAndTimelineReflectARealFlow() {
        CheckoutResult c = checkout("ops-key-0001", 499900);
        gateway(c.gatewayPaymentId(), "authorize");
        gateway(c.gatewayPaymentId(), "capture");
        awaitOrderStatus(c.orderId(), "FULFILLED");

        admin().get("/api/ops/summary").then().statusCode(200)
                .body("totalPayments", equalTo(1))
                .body("successfulPayments", equalTo(1))
                .body("fulfilments", equalTo(1))
                .body("ordersByStatus.FULFILLED", equalTo(1))
                .body("outboxBacklog", equalTo(0));

        admin().get("/api/ops/health").then().statusCode(200)
                .body("database.up", equalTo(true))
                .body("rabbitmq.up", equalTo(true))
                .body("workers.size()", greaterThanOrEqualTo(1))
                // >= 1, not 2: if "captured" overtakes "authorized", the latter is SKIPPED_STALE (not a SUCCESS sample).
                .body("processingLatencyMs.samples", greaterThanOrEqualTo(1));

        admin().get("/api/ops/timeseries").then().statusCode(200).body("size()", equalTo(60));

        admin().get("/api/payments?q=" + c.gatewayPaymentId()).then().statusCode(200)
                .body("[0].status", equalTo("CAPTURED")).body("[0].orderStatus", equalTo("FULFILLED"));

        admin().get("/api/payments/" + c.paymentId() + "/timeline").then().statusCode(200)
                .body("gateway.status", equalTo("CAPTURED"))
                .body("timeline.kind", hasItem("FULFILLED"))
                .body("timeline.source", hasItem("webhook"));

        admin().get("/api/webhook-events?status=PROCESSED").then().statusCode(200)
                .body("size()", equalTo(2)).body("[0].attemptCount", equalTo(1));
    }

    @Test
    void viewerCanReadButNotChangeAnything() {
        var viewer = RestAssured.given().auth().preemptive().basic("viewer", "test-viewer-password").contentType("application/json");
        viewer.get("/api/ops/summary").then().statusCode(200);
        RestAssured.given().auth().preemptive().basic("viewer", "test-viewer-password").get("/api/me").then().statusCode(200)
                .body("username", equalTo("viewer")).body("roles", hasItem("VIEWER")).body("faultsEnabled", equalTo(true));
        RestAssured.given().auth().preemptive().basic("viewer", "test-viewer-password").contentType("application/json")
                .post("/api/reconciliation/runs").then().statusCode(403);
        RestAssured.given().get("/api/ops/summary").then().statusCode(401);
    }

    @Test
    void prometheusMetricsAreExposedToAdminsOnly() {
        deliverSigned(eventJson("evt_metric", "payment.captured", "pay_metric", 2, "100", "CAPTURED"));
        String metrics = admin().get("/actuator/prometheus").then().statusCode(200).extract().asString();
        assertThat(metrics).contains("payguard_webhooks_total", "payguard_outbox_backlog");
        RestAssured.given().get("/actuator/prometheus").then().statusCode(401);
    }

    @Test
    void correlationIdIsGeneratedOrPropagated() {
        admin().get("/api/ops/summary").then().header("X-Correlation-Id", notNullValue());
        admin().header("X-Correlation-Id", "trace-abc-12345").get("/api/ops/summary")
                .then().header("X-Correlation-Id", equalTo("trace-abc-12345"));
        // Unsafe values (log injection attempts) are replaced, not echoed.
        admin().header("X-Correlation-Id", "bad\nvalue").get("/api/ops/summary")
                .then().header("X-Correlation-Id", org.hamcrest.Matchers.not(containsString("bad")));
    }
}
