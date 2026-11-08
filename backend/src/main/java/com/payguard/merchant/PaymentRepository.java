package com.payguard.merchant;

import com.payguard.domain.payment.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

    Optional<Payment> findByProviderAndGatewayPaymentId(String provider, String gatewayPaymentId);

    /** SELECT ... FOR UPDATE: serialises the worker, reconciliation and checkout on one payment row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.provider = :provider and p.gatewayPaymentId = :gatewayPaymentId")
    Optional<Payment> findByGatewayIdForUpdate(@Param("provider") String provider,
                                               @Param("gatewayPaymentId") String gatewayPaymentId);

    List<Payment> findByStatus(PaymentStatus status);
}
