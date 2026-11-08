package com.payguard.webhook.verification;

import com.payguard.webhook.RejectionReason;
import com.payguard.webhook.WebhookProperties;
import com.payguard.webhook.signature.WebhookSignature;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

/** Verifier for the built-in gateway simulator ("payguard-sim"). Stripe-style t=..,v1=.. header. */
@Component
public class PayGuardSimVerifier implements WebhookVerifier {

    public static final String PROVIDER = "payguard-sim";

    private final List<byte[]> secrets;
    private final java.time.Duration tolerance;
    private final Clock clock;

    public PayGuardSimVerifier(WebhookProperties props, Clock clock) {
        WebhookProperties.Provider p = props.providers().get(PROVIDER);
        if (p == null) {
            throw new IllegalStateException("payguard.webhooks.providers." + PROVIDER + " is not configured");
        }
        for (String s : p.secrets()) {
            if (s == null || s.length() < 16) {
                // Fail at startup, not on the first webhook at 3 a.m.
                throw new IllegalStateException("webhook secrets for " + PROVIDER + " must be at least 16 characters");
            }
        }
        this.secrets = p.secrets().stream().map(s -> s.getBytes(StandardCharsets.UTF_8)).toList();
        this.tolerance = p.tolerance();
        this.clock = clock;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public Result verify(HttpHeaders headers, byte[] rawBody) {
        String header = headers.getFirst(WebhookSignature.SIGNATURE_HEADER);
        WebhookSignature.Outcome outcome = WebhookSignature.verify(header, rawBody, secrets, clock.instant(), tolerance);
        return switch (outcome) {
            case VALID -> Result.ok();
            case MISSING_SIGNATURE -> Result.rejected(RejectionReason.MISSING_SIGNATURE, "no signature header");
            case MALFORMED_SIGNATURE -> Result.rejected(RejectionReason.MALFORMED_SIGNATURE, "cannot parse signature header");
            case INVALID_SIGNATURE -> Result.rejected(RejectionReason.INVALID_SIGNATURE, "signature does not match body");
            case TIMESTAMP_OUT_OF_TOLERANCE -> Result.rejected(RejectionReason.TIMESTAMP_OUT_OF_TOLERANCE,
                    "signed timestamp outside +/-" + tolerance.toSeconds() + "s");
        };
    }
}
