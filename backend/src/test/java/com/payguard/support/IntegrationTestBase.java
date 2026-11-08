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
import org.testcontainers.containers.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
    private static final PostgreSQLContainer<?> POSTGRES;

    static {
        if (EXTERNAL_DB_URL == null || EXTERNAL_DB_URL.isBlank()) {
            POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
            POSTGRES.start(); // one container for the whole test JVM; stopped by Testcontainers' Ryuk on exit
        } else {
            POSTGRES = null;
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
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    @Qualifier("webhookExecutor")
    private Executor webhookExecutor;

    @BeforeEach
    void resetDatabaseAndClient() {
        RestAssured.port = port;
        // All IT classes share one Spring context. Webhooks from the previous test are sent asynchronously;
        // let them finish before truncating, or a late delivery could leak a row into this test.
        if (webhookExecutor instanceof ThreadPoolTaskExecutor pool) {
            await().atMost(Duration.ofSeconds(15)).until(() ->
                    pool.getActiveCount() == 0 && pool.getThreadPoolExecutor().getQueue().isEmpty());
        }
        jdbc.sql("""
                TRUNCATE webhook_events, webhook_rejections, idempotency_records, payments, orders,
                         gateway.webhook_deliveries, gateway.gateway_events, gateway.gateway_refunds,
                         gateway.gateway_payments
                RESTART IDENTITY CASCADE
                """).update();
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
