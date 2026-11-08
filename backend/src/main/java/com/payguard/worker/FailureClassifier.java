package com.payguard.worker;

import com.payguard.webhook.WebhookPayloadParser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.NonTransientDataAccessResourceException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLTransientException;

/**
 * Transient vs permanent: the decision that prevents both lost events (giving up too early)
 * and infinite retry loops (retrying something that can never work).
 *
 * <p>Rule order matters: explicit classifications first, then well-known transient
 * infrastructure errors, then well-known permanent ones. Anything unknown is treated as
 * TRANSIENT - giving up on an unknown error could lose money, and the retry budget
 * (max attempts) still guarantees the loop ends.
 */
@Component
public class FailureClassifier {

    public enum Kind { TRANSIENT, PERMANENT }

    public Kind classify(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ProcessingException.Permanent) return Kind.PERMANENT;
            if (t instanceof ProcessingException.Transient) return Kind.TRANSIENT;
        }
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            // Checked first: it is a SUBCLASS of DataAccessResourceFailureException (listed as transient below).
            if (t instanceof NonTransientDataAccessResourceException) {
                return Kind.PERMANENT;
            }
            if (t instanceof TransientDataAccessException
                    || t instanceof RecoverableDataAccessException
                    || t instanceof CannotCreateTransactionException
                    || t instanceof DataAccessResourceFailureException
                    || t instanceof PessimisticLockingFailureException
                    || t instanceof OptimisticLockingFailureException
                    || t instanceof SQLTransientException
                    || t instanceof SocketTimeoutException
                    || t instanceof ConnectException) {
                return Kind.TRANSIENT;
            }
            if (t instanceof DataIntegrityViolationException
                    || t instanceof WebhookPayloadParser.MalformedPayloadException
                    || t instanceof IllegalArgumentException) {
                return Kind.PERMANENT;
            }
        }
        return Kind.TRANSIENT;
    }

    /** Short, stable label for storage and dashboards. */
    public String code(Throwable error) {
        if (error instanceof ProcessingException pe) {
            return pe.code();
        }
        return error.getClass().getSimpleName();
    }
}
