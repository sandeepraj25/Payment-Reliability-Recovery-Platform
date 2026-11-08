package com.payguard.domain.payment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static com.payguard.domain.payment.PaymentStateMachine.Decision.APPLY;
import static com.payguard.domain.payment.PaymentStateMachine.Decision.NO_OP;
import static com.payguard.domain.payment.PaymentStateMachine.Decision.REJECT;
import static com.payguard.domain.payment.PaymentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exhaustive: all 7 x 7 = 49 (from, to) pairs are checked against an independently written
 * list of legal transitions. If someone edits the state machine, exactly the changed pairs fail.
 */
class PaymentStateMachineTest {

    /** Written out by hand on purpose - NOT derived from the production table. */
    private static final Set<String> LEGAL = Set.of(
            "CREATED->AUTHORIZED", "CREATED->FAILED",
            "AUTHORIZED->CAPTURED", "AUTHORIZED->FAILED",
            "CAPTURED->REFUND_PENDING", "CAPTURED->PARTIALLY_REFUNDED", "CAPTURED->REFUNDED",
            "REFUND_PENDING->PARTIALLY_REFUNDED", "REFUND_PENDING->REFUNDED",
            "PARTIALLY_REFUNDED->REFUND_PENDING", "PARTIALLY_REFUNDED->PARTIALLY_REFUNDED",
            "PARTIALLY_REFUNDED->REFUNDED");

    static Stream<Arguments> allPairs() {
        return Arrays.stream(PaymentStatus.values())
                .flatMap(from -> Arrays.stream(PaymentStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void everyPairHasTheExpectedDecision(PaymentStatus from, PaymentStatus to) {
        PaymentStateMachine.Decision expected;
        if (LEGAL.contains(from + "->" + to)) {
            expected = APPLY;
        } else if (from == to) {
            expected = NO_OP;
        } else {
            expected = REJECT;
        }
        assertThat(PaymentStateMachine.decide(from, to)).isEqualTo(expected);
    }

    @Test
    void capturedCanNeverGoBackwards() {
        // The core "stale event" danger: a delayed payment.authorized arriving after capture.
        assertThat(PaymentStateMachine.decide(CAPTURED, AUTHORIZED)).isEqualTo(REJECT);
        assertThat(PaymentStateMachine.decide(CAPTURED, CREATED)).isEqualTo(REJECT);
        assertThat(PaymentStateMachine.decide(CAPTURED, FAILED)).isEqualTo(REJECT);
    }

    @Test
    void terminalStatesAllowNothing() {
        for (PaymentStatus to : PaymentStatus.values()) {
            assertThat(PaymentStateMachine.decide(FAILED, to)).isNotEqualTo(APPLY);
            assertThat(PaymentStateMachine.decide(REFUNDED, to)).isNotEqualTo(APPLY);
        }
        assertThat(FAILED.isTerminal()).isTrue();
        assertThat(REFUNDED.isTerminal()).isTrue();
        assertThat(CAPTURED.isTerminal()).isFalse();
    }

    @Test
    void repeatingTheSameTransitionIsANoOpNotAnError() {
        assertThat(PaymentStateMachine.decide(CAPTURED, CAPTURED)).isEqualTo(NO_OP);
        assertThatCode(() -> PaymentStateMachine.assertAllowed(CAPTURED, CAPTURED)).doesNotThrowAnyException();
    }

    @Test
    void assertAllowedThrowsWithAHelpfulMessage() {
        assertThatThrownBy(() -> PaymentStateMachine.assertAllowed(CREATED, CAPTURED))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("CREATED -> CAPTURED")
                .hasMessageContaining("AUTHORIZED");
    }

    @Test
    void nullIsRejected() {
        assertThatThrownBy(() -> PaymentStateMachine.decide(null, CAPTURED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentStateMachine.decide(CREATED, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
