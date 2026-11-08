package com.payguard.webhook;

import com.payguard.support.IntegrationTestBase;
import com.payguard.webhook.signature.WebhookSignature;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Module C requirement: "a thousand concurrent duplicate webhook requests must not create
 * duplicate records". Every request goes through the real HTTP stack, signature check and
 * PostgreSQL - nothing is mocked.
 *
 * <p>What "concurrent" means here, precisely: all N requests are put in flight at once with
 * async HTTP. The server then processes up to 200 at a time (Tomcat threads) through a pool of
 * 10 database connections (Hikari), so contention happens at the database, which is exactly
 * where the unique constraint must hold.
 */
class WebhookConcurrencyIT extends IntegrationTestBase {

    @ParameterizedTest(name = "{0} simultaneous deliveries of the same event")
    @ValueSource(ints = {100, 1000})
    void concurrentDuplicatesProduceExactlyOneEvent(int n) throws Exception {
        String body = eventJson("evt_CONC", "payment.captured", "pay_conc", 2, "499900", "CAPTURED");
        BurstResult burst = deliverBurst(body, n);
        // Transport-level retries (connection reset during the burst) are reported, not hidden.
        System.out.println(n + " deliveries, transport retries: " + burst.transportRetries());
        assertThat(burst.statuses()).as("every delivery acknowledged").hasSize(n).allMatch(st -> st == 200);

        assertThat(count("SELECT count(*) FROM webhook_events")).isEqualTo(1);
        // No lost updates on the counter either: every duplicate was counted.
        assertThat(count("SELECT duplicate_count FROM webhook_events WHERE provider_event_id = 'evt_CONC'"))
                .isGreaterThanOrEqualTo(n - 1L);
    }
}
