package com.payguard.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Credentials come only from the environment (see .env.example). The app refuses
 * to start with missing or short passwords instead of falling back to a default.
 */
@Validated
@ConfigurationProperties(prefix = "payguard.security")
public record SecurityProperties(
        @NotBlank String adminUsername,
        @NotBlank @Size(min = 12) String adminPassword,
        @NotBlank String gatewayClientId,
        @NotBlank @Size(min = 12) String gatewayClientSecret) {
}
