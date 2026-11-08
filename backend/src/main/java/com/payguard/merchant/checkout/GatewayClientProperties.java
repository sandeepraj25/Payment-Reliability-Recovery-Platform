package com.payguard.merchant.checkout;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Timeouts for merchant -> gateway calls. {@code base-url} is deliberately not bound here because
 * its default contains ${local.server.port}, which only exists once the web server has started
 * (it is read from the Environment at call time instead, see GatewayClient).
 */
@ConfigurationProperties(prefix = "payguard.gateway")
public record GatewayClientProperties(@DefaultValue("PT2S") Duration connectTimeout,
                                      @DefaultValue("PT5S") Duration readTimeout) {
}
