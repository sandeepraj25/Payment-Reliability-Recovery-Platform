package com.payguard.gateway;

/** In-process signal: "a gateway event row was written; deliver it once the transaction commits". */
public record GatewayEventCreated(String eventId) {
}
