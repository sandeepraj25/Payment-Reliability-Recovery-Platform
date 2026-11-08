package com.payguard.gateway;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface GatewayPaymentRepository extends JpaRepository<GatewayPayment, String> {

    Optional<GatewayPayment> findByCreateIdempotencyKey(String createIdempotencyKey);

    /**
     * SELECT ... FOR UPDATE. The row stays locked until the transaction ends, so two
     * concurrent "capture" calls for the same payment run one after the other: the
     * second sees CAPTURED and becomes a harmless NO_OP instead of a version conflict.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from GatewayPayment p where p.id = :id")
    Optional<GatewayPayment> findByIdForUpdate(@Param("id") String id);
}
