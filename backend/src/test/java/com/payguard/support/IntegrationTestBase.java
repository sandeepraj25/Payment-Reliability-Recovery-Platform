package com.payguard.support;

import com.payguard.webhook.signature.WebhookSignature;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import com.payguard.messaging.RabbitTopology;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;

import static org.awaitility.Awaitility.await;

/**
 * Base class for integration tests: real Spring Boot app on a random port + real PostgreSQL.
 *
 * <p>Database selection:
 * <ul>
 *   <li>{@code PAYGUARD_TEST_DB_URL} set -> use that PostgreSQL (for machines without Docker).</li>
 *   <li>otherwise -> start a throwaway PostgreSQL 16 container with Testcontainers (CI default).</li>
 * </ul>
 * Never H2: H2 does not implement PostgreSQL's ON CONFLICT / FOR UPDATE SKIP LOCKED semantics,
 * so a passing H2 test would prove nothing about the concurrency behaviour we rely on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    public static final String ADMIN_USER = "admin";
    public static final String ADMIN_PASSWORD = "test-admin-password";
    public static final byte[] WEBHOOK_SECRET = "test-webhook-secret-0123456789".getBytes(StandardCharsets.UTF_8);

    private static final String EXTERNAL_DB_URL = System.getenv("PAYGUARD_TEST_DB_URL");
    private static final String EXTERNAL_RABBIT_HOST = System.getenv("PAYGUARD_TEST_RABBIT_HOST");
    protected static final PostgreSQLContainer<?> POSTGRES;
    protected static final RabbitMQContainer RABBIT;

    static {
        if (EXTERNAL_DB_URL == null || EXTERNAL_DB_URL.isBlank()) {
            POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
            POSTGRES.start(); // one container for the whole test JVM; stopped by Testcontainers' Ryuk on exit
        } else {
            POSTGRES = null;
        }
        if (EXTERNAL_RABBIT_HOST == null || EXTERNAL_RABBIT_HOST.isBlank()) {
            RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");
            RABBIT.start();
        } else {
            RABBIT = null;
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (POSTGRES != null) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> EXTERNAL_DB_URL);
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("PAYGUARD_TEST_DB_USER", "payguard"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("PAYGUARD_TEST_DB_PASSWORD", ""));
        }
        if (RABBIT != null) {
            registry.add("spring.rabbitmq.host", RABBIT::getHost);
            registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
            registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
            registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        } else {
            registry.add("spring.rabbitmq.host", () -> EXTERNAL_RABBIT_HOST);
            registry.add("spring.rabbitmq.port", () -> System.getenv().getOrDefault("PAYGUARD_TEST_RABBIT_PORT", "5672"));
            registry.add("spring.rabbitmq.username", () -> System.getenv().getOrDefault("PAYGUARD_TEST_RABBIT_USER", "guest"));
            registry.add("spring.rabbitmq.password", () -> System.getenv().getOrDefault("PAYGUARD_TEST_RABBIT_PASSWORD", "guest"));
        }
    }

    /** Docker pause = the process is frozen: TCP stays open but nothing answers. A realistic outage. */
    protected static void pause(GenericContainer<?> c) {
        c.getDockerClient().pauseContainerCmd(c.getContainerId()).exec();
    }

    protected static void unpause(GenericContainer<?> c) {
        c.getDockerClient().unpauseContainerCmd(c.getContainerId()).exec();
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    @Qualifier("webhookExecutor")
    private Executor webhookExecutor;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @BeforeEach
    void resetDatabaseAndClient() {
        RestAssured.port = port;
        // All IT classes share one Spring context. Webhooks from the previous test are sent asynchronously;
        // let them finish before truncating, or a late delivery could leak a row into this test.
        if (webhookExecutor instanceof ThreadPoolTaskExecutor pool) {
            await().atMost(Duration.ofSeconds(15)).until(() ->
                    pool.getActiveCount() == 0 && pool.getThreadPoolExecutor().getQueue().isEmpty());
        }
        // Order matters: stop the worker, wipe the DB, THEN purge the queues (anything the publisher sent
        // before the wipe is purged; after the wipe there is nothing left to publish), then restart.
        // Ids are NOT restarted, so a stray message can never point at a NEW row with a reused id.
        MessageListenerContainer worker = listenerRegistry.getListenerContainer("payment-event-worker");
        worker.stop();
        jdbc.sql("""
                TRUNCATE processing_attempts, outbox_events, fulfilments, audit_logs, fault_injections,
                         webhook_events, webhook_rejections, idempotency_records, payments, orders,
                         gateway.webhook_deliveries, gateway.gateway_events, gateway.gateway_refunds,
                         gateway.gateway_payments
                CASCADE
                """).update();
        amqpAdmin.purgeQueue(RabbitTopology.QUEUE, false);
        amqpAdmin.purgeQueue(RabbitTopology.DLQ, false);
        worker.start();
    }

    protected static RequestSpecification admin() {
        return RestAssured.given().auth().preemptive().basic(ADMIN_USER, ADMIN_PASSWORD).contentType(ContentType.JSON);
    }

    protected long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    protected static String sign(String body) {
        return WebhookSignature.sign(Instant.now().getEpochSecond(), body.getBytes(StandardCharsets.UTF_8), WEBHOOK_SECRET);
    }

    // ------------------------------------------------------------------ Phase 2+ helpers

    public record CheckoutResult(String orderId, String paymentId, String gatewayPaymentId) {
    }

    /** Real checkout through the API: creates order, merchant payment, and the gateway payment (status CREATED). */
    protected CheckoutResult checkout(String idempotencyKey, long amountMinor) {
        var r = admin().header("Idempotency-Key", idempotencyKey)
                .body("{\"customerRef\":\"cust_t\",\"amountMinor\":" + amountMinor + ",\"currency\":\"INR\"}")
                .post("/api/checkout").then().statusCode(201).extract();
        return new CheckoutResult(r.path("orderId"), r.path("paymentId"), r.path("gatewayPaymentId"));
    }

    /** authorize | capture | fail on the simulated gateway (sends real signed webhooks after commit). */
    protected void gateway(String gatewayPaymentId, String action) {
        admin().post("/sim/gateway/payments/" + gatewayPaymentId + "/" + action).then().statusCode(200);
    }

    /** POSTs a signed webhook body directly (as if the gateway sent it) and returns the HTTP status. */
    protected int deliverSigned(String body) {
        return RestAssured.given().contentType(ContentType.JSON).body(body.getBytes(StandardCharsets.UTF_8))
                .header(WebhookSignature.SIGNATURE_HEADER, sign(body))
                .post("/api/webhooks/payguard-sim").statusCode();
    }

    protected String orderStatus(String orderId) {
        return jdbc.sql("SELECT status FROM orders WHERE id = CAST(:id AS uuid)").param("id", orderId).query(String.class).single();
    }

    protected String eventStatus(String providerEventId) {
        return jdbc.sql("SELECT processing_status FROM webhook_events WHERE provider_event_id = :id")
                .param("id", providerEventId).query(String.class).optional().orElse("MISSING");
    }

    protected void awaitOrderStatus(String orderId, String status) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                org.assertj.core.api.Assertions.assertThat(orderStatus(orderId)).isEqualTo(status));
    }

    protected void awaitEventStatus(String providerEventId, String status) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                org.assertj.core.api.Assertions.assertThat(eventStatus(providerEventId)).isEqualTo(status));
    }

    protected List<String> attemptOutcomes(String providerEventId) {
        return jdbc.sql("""
                SELECT a.outcome FROM processing_attempts a JOIN webhook_events e ON e.id = a.webhook_event_id
                 WHERE e.provider_event_id = :id ORDER BY a.attempt_no
                """).param("id", providerEventId).query(String.class).list();
    }

    protected static String eventJson(String eventId, String type, String paymentId, long version,
                                      long amount, String status, long refunded) {
        return """
                {"id":"%s","type":"%s","created_at":"2026-11-08T10:00:00Z","data":{"payment":{"id":"%s",\
                "gateway_reference":"grf_test","merchant_order_ref":"order-1","amount_minor":%d,\
                "amount_refunded_minor":%d,"currency":"INR","status":"%s","version":%d}}}"""
                .formatted(eventId, type, paymentId, amount, refunded, status, version);
    }

    /** A realistic gateway event body, exactly like GatewayPaymentService would emit. */
    protected static String eventJson(String eventId, String type, String paymentId, long version,
                                      String amountLiteral, String status) {
        return """
                {"id":"%s","type":"%s","created_at":"2026-11-08T10:00:00Z","data":{"payment":{"id":"%s",\
                "gateway_reference":"grf_test","merchant_order_ref":"order-1","amount_minor":%s,\
                "amount_refunded_minor":0,"currency":"INR","status":"%s","version":%d}}}"""
                .formatted(eventId, type, paymentId, amountLiteral, status, version);
    }
}
