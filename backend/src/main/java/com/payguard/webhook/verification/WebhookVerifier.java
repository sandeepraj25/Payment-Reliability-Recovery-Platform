package com.payguard.webhook.verification;

import com.payguard.webhook.RejectionReason;
import org.springframework.http.HttpHeaders;

/**
 * Provider-specific signature check (Module B: "provider-specific verification adapters").
 *
 * <p>Every gateway signs differently: Stripe uses {@code Stripe-Signature: t=..,v1=..},
 * Razorpay uses {@code X-Razorpay-Signature} (HMAC of the body only, no timestamp).
 * Adding a provider = adding one implementation of this interface; the ingestion
 * pipeline (persist, dedup, ack) stays exactly the same.
 */
public interface WebhookVerifier {

    /** Must match the {provider} path segment, e.g. "payguard-sim". */
    String provider();

    /** Verifies the EXACT raw bytes received. Never re-serialize JSON before verifying. */
    Result verify(HttpHeaders headers, byte[] rawBody);

    record Result(boolean valid, RejectionReason reason, String detail) {

        public static Result ok() {
            return new Result(true, null, null);
        }

        public static Result rejected(RejectionReason reason, String detail) {
            return new Result(false, reason, detail);
        }
    }
}
