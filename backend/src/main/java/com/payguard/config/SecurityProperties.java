package com.payguard.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Credentials come only from the environment (see .env.example). The app refuses
 * to start with missing or short passwords instead of falling back to a default.
 *
 * @param viewerPassword optional; when blank, no read-only VIEWER user exists
 * @param corsOrigins    browser origins allowed to call the API (the dashboard in dev mode)
 */
@Validated
@ConfigurationProperties(prefix = "payguard.security")
public record SecurityProperties(
        @NotBlank String adminUsername,
        @NotBlank @Size(min = 12) String adminPassword,
        @NotBlank String gatewayClientId,
        @NotBlank @Size(min = 12) String gatewayClientSecret,
        String viewerUsername,
        String viewerPassword,
        List<String> corsOrigins) {

    public SecurityProperties {
        if (viewerPassword != null && !viewerPassword.isBlank() && viewerPassword.length() < 12) {
            throw new IllegalArgumentException("payguard.security.viewer-password must be at least 12 characters");
        }
        corsOrigins = corsOrigins == null ? List.of() : List.copyOf(corsOrigins);
    }

    public boolean viewerEnabled() {
        return viewerPassword != null && !viewerPassword.isBlank();
    }
}
