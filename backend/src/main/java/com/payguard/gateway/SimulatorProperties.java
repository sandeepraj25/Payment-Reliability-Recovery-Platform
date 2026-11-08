package com.payguard.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Simulator settings. The webhook target URL is intentionally NOT bound here: it contains
 * ${local.server.port}, which only exists after the web server starts, so it is resolved
 * from the Environment at send time (see GatewayWebhookSender).
 */
@ConfigurationProperties(prefix = "payguard.simulator")
public record SimulatorProperties(boolean enabled, String webhookSecret) {
}
