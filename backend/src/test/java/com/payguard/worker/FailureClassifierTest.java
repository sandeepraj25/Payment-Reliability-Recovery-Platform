package com.payguard.worker;

import com.payguard.webhook.WebhookPayloadParser;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.NonTransientDataAccessResourceException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.net.SocketTimeoutException;

import static com.payguard.worker.FailureClassifier.Kind.PERMANENT;
import static com.payguard.worker.FailureClassifier.Kind.TRANSIENT;
import static org.assertj.core.api.Assertions.assertThat;

class FailureClassifierTest {

    private final FailureClassifier classifier = new FailureClassifier();

    @Test
    void explicitClassificationsWin() {
        assertThat(classifier.classify(new ProcessingException.Transient("PAYMENT_NOT_FOUND", "x"))).isEqualTo(TRANSIENT);
        assertThat(classifier.classify(new ProcessingException.Permanent("AMOUNT_MISMATCH", "x"))).isEqualTo(PERMANENT);
        // Wrapped by a framework exception: still found by walking the cause chain.
        assertThat(classifier.classify(new RuntimeException(new ProcessingException.Permanent("X", "y")))).isEqualTo(PERMANENT);
    }

    @Test
    void infrastructureHiccupsAreTransient() {
        assertThat(classifier.classify(new CannotCreateTransactionException("db down"))).isEqualTo(TRANSIENT);
        assertThat(classifier.classify(new QueryTimeoutException("slow"))).isEqualTo(TRANSIENT);
        assertThat(classifier.classify(new CannotAcquireLockException("lock"))).isEqualTo(TRANSIENT);
        assertThat(classifier.classify(new ObjectOptimisticLockingFailureException("Payment", "id"))).isEqualTo(TRANSIENT);
        assertThat(classifier.classify(new DataAccessResourceFailureException("conn reset"))).isEqualTo(TRANSIENT);
        assertThat(classifier.classify(new RuntimeException(new SocketTimeoutException()))).isEqualTo(TRANSIENT);
    }

    @Test
    void dataProblemsArePermanent() {
        assertThat(classifier.classify(new DataIntegrityViolationException("check violated"))).isEqualTo(PERMANENT);
        assertThat(classifier.classify(new WebhookPayloadParser.MalformedPayloadException("bad"))).isEqualTo(PERMANENT);
        assertThat(classifier.classify(new IllegalArgumentException("bad arg"))).isEqualTo(PERMANENT);
    }

    @Test
    void resourceFailureIsTransientButItsNonTransientParentIsPermanent() {
        // DataAccessResourceFailureException EXTENDS NonTransientDataAccessResourceException:
        // the subclass ("DB unreachable") is retried, the parent (e.g. misconfiguration) is not.
        assertThat(classifier.classify(new NonTransientDataAccessResourceException("bad config"))).isEqualTo(PERMANENT);
    }

    @Test
    void unknownErrorsAreTransientBecauseTheRetryBudgetStillEndsTheLoop() {
        assertThat(classifier.classify(new IllegalStateException("???"))).isEqualTo(TRANSIENT);
    }

    @Test
    void codes() {
        assertThat(classifier.code(new ProcessingException.Permanent("AMOUNT_MISMATCH", "x"))).isEqualTo("AMOUNT_MISMATCH");
        assertThat(classifier.code(new QueryTimeoutException("x"))).isEqualTo("QueryTimeoutException");
    }
}
