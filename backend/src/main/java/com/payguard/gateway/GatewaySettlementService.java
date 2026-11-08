package com.payguard.gateway;

import com.payguard.common.id.Ids;
import com.payguard.faults.FaultInjector;
import com.payguard.faults.FaultPoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Simulated settlement: the gateway pays out captured money in batches, minus its fee.
 *
 * <p>Fee = 2% computed with INTEGER arithmetic on paise ({@code gross * 2 / 100}, rounded down).
 * No floating point ever touches money: 0.1 + 0.2 != 0.3 in binary floating point.
 */
@Service
@ConditionalOnProperty(prefix = "payguard.simulator", name = "enabled", havingValue = "true")
public class GatewaySettlementService {

    static final long FEE_PERCENT = 2;

    private final JdbcClient jdbc;
    private final FaultInjector faults;

    public GatewaySettlementService(JdbcClient jdbc, FaultInjector faults) {
        this.jdbc = jdbc;
        this.faults = faults;
    }

    public record Item(String paymentId, long grossMinor, long feeMinor, long netMinor, String currency) {
    }

    public record Batch(String id, OffsetDateTime cutoff, OffsetDateTime createdAt, List<Item> items) {
    }

    static long feeFor(long grossMinor) {
        return grossMinor * FEE_PERCENT / 100;
    }

    @Transactional
    public Batch run(Instant cutoff) {
        // Lock candidate payments so a concurrent run cannot settle the same payment
        // (UNIQUE(payment_id) on settlement_items would reject it anyway).
        List<Item> candidates = jdbc.sql("""
                SELECT p.id, p.amount_minor - p.amount_refunded_minor AS gross, p.currency
                  FROM gateway.gateway_payments p
                 WHERE p.status IN ('CAPTURED','PARTIALLY_REFUNDED')
                   AND p.captured_at <= :cutoff
                   AND NOT EXISTS (SELECT 1 FROM gateway.settlement_items i WHERE i.payment_id = p.id)
                 ORDER BY p.captured_at, p.id
                   FOR UPDATE OF p
                """)
                .param("cutoff", Timestamp.from(cutoff))
                .query((rs, n) -> {
                    long gross = rs.getLong("gross");
                    long fee = feeFor(gross);
                    return new Item(rs.getString("id"), gross, fee, gross - fee, rs.getString("currency"));
                })
                .list();

        List<Item> items = new ArrayList<>(candidates);
        if (!items.isEmpty() && faults.shouldFire(FaultPoint.GATEWAY_SETTLEMENT_OMIT)) {
            items.remove(0); // "the gateway forgot one": reconciliation must report MISSING_SETTLEMENT
        }

        String batchId = Ids.newId("stl");
        jdbc.sql("INSERT INTO gateway.settlement_batches (id, cutoff) VALUES (:id, :cutoff)")
                .param("id", batchId).param("cutoff", Timestamp.from(cutoff)).update();
        for (Item i : items) {
            jdbc.sql("""
                    INSERT INTO gateway.settlement_items (batch_id, payment_id, gross_minor, fee_minor, net_minor, currency)
                    VALUES (:b, :p, :g, :f, :n, :c)
                    """)
                    .param("b", batchId).param("p", i.paymentId()).param("g", i.grossMinor())
                    .param("f", i.feeMinor()).param("n", i.netMinor()).param("c", i.currency())
                    .update();
        }
        return batchesSince(Instant.EPOCH).stream().filter(b -> b.id().equals(batchId)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Batch> batchesSince(Instant since) {
        record Header(String id, OffsetDateTime cutoff, OffsetDateTime createdAt) {
        }
        List<Header> headers = jdbc.sql("""
                SELECT id, cutoff, created_at FROM gateway.settlement_batches WHERE created_at >= :since ORDER BY created_at, id
                """)
                .param("since", Timestamp.from(since))
                .query((rs, n) -> new Header(rs.getString("id"), rs.getObject("cutoff", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
        List<Batch> result = new ArrayList<>();
        for (Header h : headers) {
            List<Item> items = jdbc.sql("""
                    SELECT payment_id, gross_minor, fee_minor, net_minor, currency
                      FROM gateway.settlement_items WHERE batch_id = :b ORDER BY payment_id
                    """)
                    .param("b", h.id())
                    .query((rs, n) -> new Item(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5)))
                    .list();
            result.add(new Batch(h.id(), h.cutoff(), h.createdAt(), items));
        }
        return result;
    }
}
