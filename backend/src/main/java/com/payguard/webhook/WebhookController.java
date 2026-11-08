package com.payguard.webhook;

import com.payguard.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.regex.Pattern;

@RestController
public class WebhookController {

    private static final Pattern PROVIDER = Pattern.compile("^[a-z0-9-]{1,32}$");

    private final WebhookIngestionService ingestion;
    private final WebhookEventStore store;

    public WebhookController(WebhookIngestionService ingestion, WebhookEventStore store) {
        this.ingestion = ingestion;
        this.store = store;
    }

    /**
     * {@code byte[]} body: Spring hands us the EXACT bytes on the wire. Binding to a DTO
     * (or even to String) first would let a parser normalise whitespace or encoding, and
     * the HMAC would then be computed over different bytes than the gateway signed.
     */
    @PostMapping("/api/webhooks/{provider}")
    public WebhookIngestionService.Accepted receive(@PathVariable String provider,
                                                    @RequestHeader HttpHeaders headers,
                                                    @RequestBody(required = false) byte[] body,
                                                    HttpServletRequest request) {
        if (!PROVIDER.matcher(provider).matches()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown webhook provider");
        }
        return ingestion.ingest(provider, headers, body, request.getRemoteAddr());
    }

    // ---------- operator read API (ADMIN) ----------

    @GetMapping("/api/webhook-events")
    public List<WebhookEventStore.EventRow> events(@RequestParam(required = false) String gatewayPaymentId,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String eventId,
                                                   @RequestParam(defaultValue = "50") int limit) {
        return store.recentEvents(blankToNull(gatewayPaymentId), blankToNull(status), blankToNull(eventId), clamp(limit));
    }

    @GetMapping("/api/webhook-events/{id}")
    public WebhookEventStore.EventRow event(@PathVariable long id) {
        return store.findEvent(id).orElseThrow(() -> ApiException.notFound("Webhook event", id));
    }

    @GetMapping("/api/webhook-rejections")
    public List<WebhookEventStore.RejectionRow> rejections(@RequestParam(defaultValue = "50") int limit) {
        return store.recentRejections(clamp(limit));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, 500));
    }
}
