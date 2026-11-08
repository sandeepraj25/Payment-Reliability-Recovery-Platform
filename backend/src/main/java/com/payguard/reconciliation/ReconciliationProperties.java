package com.payguard.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param scheduled                 run automatically on {@code interval}
 * @param interval                  how often (configurable, as required)
 * @param lookback                  window of payments compared on each run
 * @param settlementExpectedWithin  after this long without settlement a payment is DELAYED_SETTLEMENT
 * @param duplicateThreshold        an event delivered this many extra times is reported (informational)
 * @param staleRunAfter             a RUNNING run older than this is treated as crashed (ABANDONED)
 */
@ConfigurationProperties(prefix = "payguard.reconciliation")
public record ReconciliationProperties(@DefaultValue("true") boolean scheduled,
                                       @DefaultValue("PT5M") Duration interval,
                                       @DefaultValue("P7D") Duration lookback,
                                       @DefaultValue("PT48H") Duration settlementExpectedWithin,
                                       @DefaultValue("3") int duplicateThreshold,
                                       @DefaultValue("PT30M") Duration staleRunAfter) {
}
