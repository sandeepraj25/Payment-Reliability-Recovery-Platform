package com.payguard.faults;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Demo/test-only fault switches. When {@code payguard.faults.enabled=false} (the default) every
 * check returns immediately without touching the database, and the arming API does not exist.
 *
 * <p>Faults are stored in PostgreSQL, not in memory, so the API process can arm a fault that fires
 * inside the separate worker process.
 *
 * <p>Consuming a fault runs in its OWN transaction (REQUIRES_NEW). Otherwise the crash it triggers
 * would roll back the "remaining - 1" too, and the fault would fire forever.
 */
@Component
public class FaultInjector {

    private static final Logger log = LoggerFactory.getLogger(FaultInjector.class);

    private final boolean enabled;
    private final JdbcClient jdbc;
    private final TransactionTemplate independentTx;

    public FaultInjector(@Value("${payguard.faults.enabled:false}") boolean enabled, JdbcClient jdbc,
                         PlatformTransactionManager txManager) {
        this.enabled = enabled;
        this.jdbc = jdbc;
        this.independentTx = new TransactionTemplate(txManager);
        this.independentTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Atomically consumes one shot of the fault, if armed. */
    public boolean shouldFire(FaultPoint point) {
        if (!enabled) {
            return false;
        }
        Boolean fired = independentTx.execute(s -> jdbc.sql("""
                        UPDATE fault_injections SET remaining = remaining - 1
                         WHERE fault_point = :p AND remaining > 0
                        RETURNING remaining
                        """)
                .param("p", point.name())
                .query(Integer.class)
                .optional()
                .isPresent());
        if (Boolean.TRUE.equals(fired)) {
            log.warn("FAULT INJECTED point={}", point);
            return true;
        }
        return false;
    }

    /** Throws {@link SimulatedCrashException} if the fault is armed. */
    public void crashIfArmed(FaultPoint point) {
        if (shouldFire(point)) {
            throw new SimulatedCrashException(point);
        }
    }

    public void arm(FaultPoint point, int times, String actor) {
        requireEnabled();
        jdbc.sql("""
                INSERT INTO fault_injections (fault_point, remaining, armed_by) VALUES (:p, :n, :actor)
                ON CONFLICT (fault_point) DO UPDATE SET remaining = :n, armed_by = :actor, armed_at = now()
                """)
                .param("p", point.name()).param("n", times).param("actor", actor)
                .update();
    }

    public void disarmAll() {
        requireEnabled();
        jdbc.sql("DELETE FROM fault_injections").update();
    }

    public record ArmedFault(String faultPoint, int remaining, String armedBy) {
    }

    public List<ArmedFault> armed() {
        if (!enabled) {
            return List.of();
        }
        return jdbc.sql("SELECT fault_point, remaining, armed_by FROM fault_injections WHERE remaining > 0 ORDER BY fault_point")
                .query((rs, n) -> new ArmedFault(rs.getString(1), rs.getInt(2), rs.getString(3)))
                .list();
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException("fault injection is disabled (payguard.faults.enabled=false)");
        }
    }
}
