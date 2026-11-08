package com.payguard.common.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    @Test
    void sameInputGivesSameFingerprint() {
        assertThat(RequestFingerprint.of("cust_1", "499900", "INR"))
                .isEqualTo(RequestFingerprint.of("cust_1", "499900", "INR"))
                .hasSize(64);
    }

    @Test
    void differentAmountGivesDifferentFingerprint() {
        assertThat(RequestFingerprint.of("cust_1", "499900", "INR"))
                .isNotEqualTo(RequestFingerprint.of("cust_1", "499901", "INR"));
    }

    @Test
    void lengthPrefixPreventsConcatenationCollisions() {
        // Without length prefixes both would hash "abc".
        assertThat(RequestFingerprint.of("ab", "c")).isNotEqualTo(RequestFingerprint.of("a", "bc"));
    }

    @Test
    void nullAndEmptyAreDifferent() {
        assertThat(RequestFingerprint.of((String) null)).isNotEqualTo(RequestFingerprint.of(""));
    }
}
