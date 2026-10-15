package com.tiendaposs.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public final class FinanceRepos {
    private FinanceRepos() {
    }

    public interface FinanceCategoryRepository extends JpaRepository<FinanceCategory, Long> {
        List<FinanceCategory> findByTenantIdOrderByKindAscNameAsc(Long tenantId);

        Optional<FinanceCategory> findByIdAndTenantId(Long id, Long tenantId);

        @Query("select c from FinanceCategory c where c.tenantId = ?1 and c.kind = ?2 and lower(c.name) = lower(?3)")
        Optional<FinanceCategory> findByName(Long tenantId, String kind, String name);
    }

    public interface FinanceEntryRepository extends JpaRepository<FinanceEntry, Long> {
        Optional<FinanceEntry> findByIdAndTenantId(Long id, Long tenantId);

        @Query("""
                select e from FinanceEntry e where e.tenantId = :t and e.entryDate >= :from and e.entryDate <= :to
                  and (:kind is null or e.kind = :kind) and (:categoryId is null or e.categoryId = :categoryId)
                order by e.entryDate desc, e.id desc""")
        List<FinanceEntry> search(Long t, LocalDate from, LocalDate to, String kind, Long categoryId);
    }

    public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, Long> {
        List<SupplierPayment> findBySupplierIdOrderByPaidOnDescIdDesc(Long supplierId);

        @Query("select p from SupplierPayment p where p.tenantId = ?1 and p.paidOn >= ?2 and p.paidOn <= ?3 order by p.paidOn desc, p.id desc")
        List<SupplierPayment> between(Long tenantId, LocalDate from, LocalDate to);
    }

    public interface SupplierPaymentAllocationRepository extends JpaRepository<SupplierPaymentAllocation, Long> {
        List<SupplierPaymentAllocation> findByPaymentId(Long paymentId);
    }
}
