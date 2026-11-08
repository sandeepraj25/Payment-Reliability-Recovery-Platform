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
        String signature = sign(body);

        ExecutorService pool = Executors.newFixedThreadPool(64);
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(pool)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/webhooks/payguard-sim"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header(WebhookSignature.SIGNATURE_HEADER, signature)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            futures.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(120, TimeUnit.SECONDS);
        pool.shutdown();

        int ok = 0, accepted = 0, duplicate = 0;
        for (var f : futures) {
            HttpResponse<String> r = f.get();
            if (r.statusCode() == 200) ok++;
            if (r.body().contains("\"ACCEPTED\"")) accepted++;
            if (r.body().contains("\"DUPLICATE\"")) duplicate++;
        }

        assertThat(ok).as("every delivery acknowledged").isEqualTo(n);
        assertThat(accepted).as("exactly one winner").isEqualTo(1);
        assertThat(duplicate).isEqualTo(n - 1);
        assertThat(count("SELECT count(*) FROM webhook_events")).isEqualTo(1);
        // No lost updates on the counter either: every duplicate was counted.
        assertThat(count("SELECT duplicate_count FROM webhook_events WHERE provider_event_id = 'evt_CONC'"))
                .isEqualTo(n - 1);
    }
}
