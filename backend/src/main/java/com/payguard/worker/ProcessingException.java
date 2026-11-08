package com.payguard.worker;

/**
 * Explicitly classified worker failures. {@code code} is stored in processing_attempts.error_class
 * style output and shown to operators, so keep codes stable.
 */
public abstract sealed class ProcessingException extends RuntimeException
        permits ProcessingException.Transient, ProcessingException.Permanent {

    private final String code;

    protected ProcessingException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Might succeed later (e.g. the payment row is not linked yet, DB lock timeout). Retry with backoff. */
    public static final class Transient extends ProcessingException {
        public Transient(String code, String message) {
            super(code, message);
        }
    }

    /** Will fail the same way every time (amount mismatch, impossible transition). Do not retry: dead-letter. */
    public static final class Permanent extends ProcessingException {
        public Permanent(String code, String message) {
            super(code, message);
        }
    }
}
