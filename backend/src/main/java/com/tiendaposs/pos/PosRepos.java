package com.tiendaposs.pos;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class PosRepos {
    private PosRepos() {
    }

    public interface RegisterRepository extends JpaRepository<Register, Long> {
        List<Register> findByTenantIdOrderByIdAsc(Long tenantId);

        Optional<Register> findByIdAndTenantId(Long id, Long tenantId);

        @Query(value = "select id from branches where tenant_id = ?1 and is_main", nativeQuery = true)
        Long mainBranch(Long tenantId);

        @Modifying
        @Query(value = "insert into branches (tenant_id, name, phone, is_main) values (?1, 'Principal', ?2, true)", nativeQuery = true)
        void createMainBranch(Long tenantId, String phone);

        @Query("select count(r) > 0 from Register r where r.tenantId = ?1 and lower(r.name) = lower(?2) and (?3 is null or r.id <> ?3)")
        boolean nameTaken(Long tenantId, String name, Long excludeId);
    }

    public interface ShiftRepository extends JpaRepository<Shift, Long> {
        Optional<Shift> findByIdAndTenantId(Long id, Long tenantId);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select s from Shift s where s.id = ?1 and s.tenantId = ?2")
        Optional<Shift> lockById(Long id, Long tenantId);

        Optional<Shift> findFirstByTenantIdAndUserIdAndStatus(Long tenantId, Long userId, String status);

        Optional<Shift> findFirstByRegisterIdAndStatus(Long registerId, String status);

        List<Shift> findByTenantIdAndStatusOrderByOpenedAtAsc(Long tenantId, String status);

        @Query("""
                select s from Shift s where s.tenantId = :tenantId
                  and (:userId is null or s.userId = :userId)
                  and (:registerId is null or s.registerId = :registerId)
                  and (:status is null or s.status = :status)
                  and s.openedAt >= :from and s.openedAt < :to
                order by s.openedAt desc""")
        Page<Shift> search(Long tenantId, Long userId, Long registerId, String status, Instant from, Instant to, Pageable pageable);
    }

    public interface CashMovementRepository extends JpaRepository<CashMovement, Long> {
        List<CashMovement> findByShiftIdOrderByCreatedAtAsc(Long shiftId);
    }

    public interface SaleRepository extends JpaRepository<Sale, Long> {
        Optional<Sale> findByIdAndTenantId(Long id, Long tenantId);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select s from Sale s where s.id = ?1 and s.tenantId = ?2")
        Optional<Sale> lockById(Long id, Long tenantId);

        Optional<Sale> findByTenantIdAndClientRef(Long tenantId, String clientRef);

        Optional<Sale> findByTenantIdAndNumber(Long tenantId, Long number);

        List<Sale> findByTenantIdAndStatusInOrderByCreatedAtAsc(Long tenantId, Collection<String> statuses);

        long countByTenantIdAndStatus(Long tenantId, String status);

        List<Sale> findByShiftIdAndStatus(Long shiftId, String status);

        @Query("select count(s) from Sale s where s.shiftId = ?1 and s.status in ('ABIERTA','EN_ESPERA')")
        long countOpenInShift(Long shiftId);

        @Query("""
                select s from Sale s where s.tenantId = :tenantId and s.status in ('PAGADA','ANULADA')
                  and (:userId is null or s.userId = :userId)
                  and (:shiftId is null or s.shiftId = :shiftId)
                  and (:status is null or s.status = :status)
                  and (:number is null or s.number = :number)
                  and (:minTotal is null or s.total >= :minTotal)
                  and (:maxTotal is null or s.total <= :maxTotal)
                  and (:onlyReview = false or (s.reviewFlags is not null and s.reviewedAt is null))
                  and s.completedAt >= :from and s.completedAt < :to
                order by s.completedAt desc, s.id desc""")
        Page<Sale> history(Long tenantId, Long userId, Long shiftId, String status, Long number, BigDecimal minTotal,
                           BigDecimal maxTotal, boolean onlyReview, Instant from, Instant to, Pageable pageable);
    }

    public interface SaleLineRepository extends JpaRepository<SaleLine, Long> {
        List<SaleLine> findBySaleIdOrderBySortOrderAscIdAsc(Long saleId);

        void deleteBySaleId(Long saleId);
    }

    public interface PaymentRepository extends JpaRepository<Payment, Long> {
        List<Payment> findBySaleIdOrderByIdAsc(Long saleId);

        List<Payment> findByShiftId(Long shiftId);

        @Query("select count(p) > 0 from Payment p where p.tenantId = ?1 and p.reference = ?2 and p.method = ?3 and p.saleId <> ?4")
        boolean referenceUsed(Long tenantId, String reference, String method, Long saleId);
    }

    public interface SaleReturnRepository extends JpaRepository<SaleReturn, Long> {
        List<SaleReturn> findBySaleIdOrderByIdAsc(Long saleId);

        List<SaleReturn> findByShiftId(Long shiftId);
    }

    public interface SaleReturnLineRepository extends JpaRepository<SaleReturnLine, Long> {
        List<SaleReturnLine> findByReturnIdIn(Collection<Long> returnIds);
    }

    public interface CustomerRepository extends JpaRepository<Customer, Long> {
        Optional<Customer> findByIdAndTenantId(Long id, Long tenantId);

        @Query("""
                select c from Customer c where c.tenantId = ?1 and c.active = true and (lower(c.name) like ?2
                  or c.docNumber like ?2 or c.phone like ?2) order by c.name""")
        List<Customer> search(Long tenantId, String like, Pageable pageable);

        Optional<Customer> findByTenantIdAndDocNumber(Long tenantId, String docNumber);
    }

    public interface PromotionRepository extends JpaRepository<Promotion, Long> {
        List<Promotion> findByTenantIdOrderByIdDesc(Long tenantId);

        List<Promotion> findByTenantIdAndActiveTrue(Long tenantId);

        Optional<Promotion> findByIdAndTenantId(Long id, Long tenantId);
    }
}
