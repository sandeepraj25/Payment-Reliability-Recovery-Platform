package com.payguard.common.crypto;

/**
 * SHA-256 fingerprint of a request, used to detect "same idempotency key, different request".
 *
 * <p>We fingerprint the validated request FIELDS, not the raw JSON, so that
 * {"a":1,"b":2} and {"b":2, "a":1} (same meaning, different bytes) match.
 *
 * <p>Each part is length-prefixed. Without that, ("ab","c") and ("a","bc") would
 * both join to "abc" and collide.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null) {
                sb.append("-1:|");
            } else {
                sb.append(part.length()).append(':').append(part).append('|');
            }
        }
        return Hashing.sha256Hex(sb.toString());
    }
}
