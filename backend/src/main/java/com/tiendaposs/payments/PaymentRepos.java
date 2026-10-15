package com.tiendaposs.payments;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public final class PaymentRepos {
    private PaymentRepos() {
    }

    public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {
        List<PaymentEvent> findByPaymentIdOrderByCreatedAtAsc(Long paymentId);

        List<PaymentEvent> findTop200ByTenantIdOrderByCreatedAtDesc(Long tenantId);
    }

    public interface PaymentAccountRepository extends JpaRepository<PaymentAccount, Long> {
        List<PaymentAccount> findByTenantIdOrderByIdAsc(Long tenantId);

        Optional<PaymentAccount> findByIdAndTenantId(Long id, Long tenantId);
    }

    public interface PaymentIntegrationRepository extends JpaRepository<PaymentIntegration, Long> {
        List<PaymentIntegration> findByTenantId(Long tenantId);

        Optional<PaymentIntegration> findByPublicKey(String publicKey);
    }

    public interface ReconciliationRepository extends JpaRepository<Reconciliation, Long> {
        List<Reconciliation> findTop30ByTenantIdOrderByCreatedAtDesc(Long tenantId);

        Optional<Reconciliation> findByIdAndTenantId(Long id, Long tenantId);
    }

    public interface ReconciliationRowRepository extends JpaRepository<ReconciliationRow, Long> {
        List<ReconciliationRow> findByReconciliationIdOrderByIdAsc(Long reconciliationId);
    }
}
