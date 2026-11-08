package com.payguard.webhook;

/** Values must match the CHECK constraint on webhook_rejections.reason (V3 migration). */
public enum RejectionReason {
    MISSING_SIGNATURE,
    MALFORMED_SIGNATURE,
    INVALID_SIGNATURE,
    TIMESTAMP_OUT_OF_TOLERANCE,
    MALFORMED_PAYLOAD
}
