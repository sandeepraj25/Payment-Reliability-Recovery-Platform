package com.payguard.webhook;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Per-provider webhook settings.
 *
 * @param maxBodyBytes reject bigger bodies before doing any crypto or JSON work (cheap DoS guard)
 * @param providers    provider name (the {provider} path segment) -> its secrets and timestamp tolerance
 */
@Validated
@ConfigurationProperties(prefix = "payguard.webhooks")
public record WebhookProperties(@Positive int maxBodyBytes, @NotEmpty Map<String, @Valid Provider> providers) {

    /**
     * @param secrets   all currently valid HMAC secrets. Two entries during a rotation, one otherwise.
     * @param tolerance how old (or how far in the future) a signed timestamp may be
     */
    public record Provider(@NotEmpty List<String> secrets, @NotNull Duration tolerance) {
    }
}
