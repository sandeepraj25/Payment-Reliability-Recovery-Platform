package com.payguard.webhook.signature;

import com.payguard.common.crypto.Hashing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * HMAC-SHA256 webhook signatures in the format {@code t=<unix seconds>,v1=<hex>}.
 *
 * <p>Signed bytes = {@code "<t>." + rawBody}. Including the timestamp INSIDE the
 * signature means an attacker cannot take an old, valid request and change its
 * timestamp - that would break the signature. Combined with a tolerance window and
 * event-id deduplication this gives replay protection:
 * <ul>
 *   <li>replayed within the window  -> passes signature, then deduplicated by event id</li>
 *   <li>replayed after the window   -> rejected as TIMESTAMP_OUT_OF_TOLERANCE</li>
 * </ul>
 *
 * <p>Several {@code v1=} entries and several secrets are supported so a secret can be
 * rotated without downtime (old and new secret are both valid for a while).
 */
public final class WebhookSignature {

    /** Shared "wire protocol" between the gateway simulator and the merchant verifier. */
    public static final String SIGNATURE_HEADER = "PayGuard-Signature";
    public static final String EVENT_ID_HEADER = "PayGuard-Event-Id";

    private WebhookSignature() {
    }

    public enum Outcome {
        VALID,
        MISSING_SIGNATURE,
        MALFORMED_SIGNATURE,
        INVALID_SIGNATURE,
        TIMESTAMP_OUT_OF_TOLERANCE
    }

    /** Builds the header value. Used by the gateway simulator and by tests. */
    public static String sign(long unixSeconds, byte[] rawBody, byte[] secret) {
        return "t=" + unixSeconds + ",v1=" + Hashing.hmacSha256Hex(secret, signedPayload(unixSeconds, rawBody));
    }

    public static Outcome verify(String header, byte[] rawBody, List<byte[]> secrets,
                                 Instant now, Duration tolerance) {
        if (header == null || header.isBlank()) {
            return Outcome.MISSING_SIGNATURE;
        }

        Long timestamp = null;
        List<String> candidates = new ArrayList<>();
        for (String element : header.split(",")) {
            int eq = element.indexOf('=');
            if (eq <= 0) {
                return Outcome.MALFORMED_SIGNATURE;
            }
            String key = element.substring(0, eq).trim();
            String value = element.substring(eq + 1).trim();
            switch (key) {
                case "t" -> {
                    if (timestamp != null) {
                        return Outcome.MALFORMED_SIGNATURE; // two timestamps: ambiguous, refuse
                    }
                    try {
                        timestamp = Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        return Outcome.MALFORMED_SIGNATURE;
                    }
                }
                case "v1" -> candidates.add(value);
                default -> { /* unknown scheme (e.g. future v2): ignore */ }
            }
        }
        if (timestamp == null || candidates.isEmpty()) {
            return Outcome.MALFORMED_SIGNATURE;
        }

        // 1) Authenticity FIRST, so the reported reason is accurate:
        //    "TIMESTAMP_OUT_OF_TOLERANCE" then really means "genuine but old" (a replay).
        byte[] payload = signedPayload(timestamp, rawBody);
        boolean matched = false;
        for (byte[] secret : secrets) {
            byte[] expected = Hashing.hmacSha256Hex(secret, payload).getBytes(StandardCharsets.US_ASCII);
            for (String candidate : candidates) {
                // MessageDigest.isEqual is constant-time: it does not return early at the first
                // differing byte, so response timing does not leak how much of a guess was right.
                // String.equals() DOES return early and must not be used for secrets.
                if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.US_ASCII))) {
                    matched = true;
                }
            }
        }
        if (!matched) {
            return Outcome.INVALID_SIGNATURE;
        }

        // 2) Freshness. abs() also rejects timestamps from the future.
        long ageSeconds = Math.abs(now.getEpochSecond() - timestamp);
        if (ageSeconds > tolerance.toSeconds()) {
            return Outcome.TIMESTAMP_OUT_OF_TOLERANCE;
        }
        return Outcome.VALID;
    }

    /** Concatenates at the BYTE level: the body is never decoded/re-encoded, so it cannot change. */
    private static byte[] signedPayload(long unixSeconds, byte[] rawBody) {
        byte[] prefix = (unixSeconds + ".").getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[prefix.length + rawBody.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(rawBody, 0, out, prefix.length, rawBody.length);
        return out;
    }
}
