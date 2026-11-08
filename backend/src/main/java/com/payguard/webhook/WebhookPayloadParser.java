package com.payguard.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payguard.domain.payment.PaymentStatus;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Schema validation for VERIFIED webhook bodies.
 *
 * <p>Runs only after the signature check. A signed-but-malformed body is a gateway bug;
 * it is recorded as a rejection, never as an event, so garbage never enters the
 * dedup key space or the processing pipeline.
 *
 * <p>Unknown event types are NOT errors. Gateways add new event types all the time; a
 * strict "reject unknown" would make the gateway retry them forever. We store them as
 * IGNORED and acknowledge.
 */
@Component
public class WebhookPayloadParser {

    public static final Set<String> KNOWN_TYPES =
            Set.of("payment.authorized", "payment.captured", "payment.failed", "payment.refunded");

    private static final Pattern EVENT_ID = Pattern.compile("^[A-Za-z0-9_\\-]{1,128}$");
    private static final Pattern PAYMENT_ID = Pattern.compile("^[A-Za-z0-9_\\-]{1,64}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private final ObjectMapper objectMapper;

    public WebhookPayloadParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** The validated, typed view of one event. {@code paymentId} etc. are null for unknown types. */
    public record ParsedEvent(String eventId, String type, boolean known, Instant createdAt,
                              String rawBodyText, String paymentId, Long paymentVersion,
                              Long amountMinor, String currency, PaymentStatus paymentStatus,
                              Long amountRefundedMinor) {
    }

    public static class MalformedPayloadException extends RuntimeException {
        public MalformedPayloadException(String message) {
            super(message);
        }
    }

    public ParsedEvent parse(byte[] rawBody) {
        String text = strictUtf8(rawBody);
        JsonNode root;
        try {
            root = objectMapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new MalformedPayloadException("body is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new MalformedPayloadException("body must be a JSON object");
        }
        if (text.contains("\\u0000")) {
            throw new MalformedPayloadException("NUL characters are not allowed"); // PostgreSQL jsonb/text reject them
        }

        String eventId = requiredText(root, "id", EVENT_ID);
        String type = requiredText(root, "type", null);
        if (type.length() > 64) {
            throw new MalformedPayloadException("type too long");
        }
        Instant createdAt = optionalInstant(root, "created_at");

        if (!KNOWN_TYPES.contains(type)) {
            return new ParsedEvent(eventId, type, false, createdAt, text, null, null, null, null, null, null);
        }

        JsonNode payment = root.path("data").path("payment");
        if (!payment.isObject()) {
            throw new MalformedPayloadException("data.payment is required for " + type);
        }
        String paymentId = requiredText(payment, "id", PAYMENT_ID);
        long version = requiredNonNegativeLong(payment, "version");
        long amount = requiredNonNegativeLong(payment, "amount_minor");
        if (amount == 0) {
            throw new MalformedPayloadException("data.payment.amount_minor must be positive");
        }
        String currency = requiredText(payment, "currency", CURRENCY);
        PaymentStatus status;
        try {
            status = PaymentStatus.valueOf(requiredText(payment, "status", null));
        } catch (IllegalArgumentException e) {
            throw new MalformedPayloadException("data.payment.status is not a known payment status");
        }
        long refunded = payment.has("amount_refunded_minor") ? requiredNonNegativeLong(payment, "amount_refunded_minor") : 0L;
        if (refunded > amount) {
            throw new MalformedPayloadException("data.payment.amount_refunded_minor exceeds amount_minor");
        }
        return new ParsedEvent(eventId, type, true, createdAt, text, paymentId, version, amount, currency, status, refunded);
    }

    // ------------------------------------------------------------------ helpers

    /** Reject invalid UTF-8 instead of silently replacing bad bytes with '?' (the stored body must equal what was signed). */
    private static String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new MalformedPayloadException("body is not valid UTF-8");
        }
    }

    private static String requiredText(JsonNode node, String field, Pattern pattern) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw new MalformedPayloadException(field + " is required and must be a string");
        }
        if (pattern != null && !pattern.matcher(v.asText()).matches()) {
            throw new MalformedPayloadException(field + " has an invalid format");
        }
        return v.asText();
    }

    /** Money and versions must be JSON integers. 4999.5 or "4999" are rejected, never rounded. */
    private static long requiredNonNegativeLong(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong()) {
            throw new MalformedPayloadException("data.payment." + field + " must be an integer");
        }
        long value = v.asLong();
        if (value < 0) {
            throw new MalformedPayloadException("data.payment." + field + " must not be negative");
        }
        return value;
    }

    private static Instant optionalInstant(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            return Instant.parse(v.asText());
        } catch (DateTimeParseException e) {
            throw new MalformedPayloadException(field + " must be an ISO-8601 instant");
        }
    }
}
