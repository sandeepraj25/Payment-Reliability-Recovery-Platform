package com.payguard.gateway;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GatewayRefundRepository extends JpaRepository<GatewayRefund, String> {

    Optional<GatewayRefund> findByPaymentIdAndIdempotencyKey(String paymentId, String idempotencyKey);

    List<GatewayRefund> findByPaymentIdOrderByCreatedAtAsc(String paymentId);
}
