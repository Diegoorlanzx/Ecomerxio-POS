package com.tiendaposs.remissions;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public final class RemissionRepos {
    private RemissionRepos() {
    }

    public interface RemissionRepository extends JpaRepository<Remission, Long> {
        Optional<Remission> findByIdAndTenantId(Long id, Long tenantId);

        @Query("""
                select r from Remission r where r.tenantId = ?1
                  and (?2 = '' or r.status = ?2)
                  and (?3 is null or r.customerId = ?3)
                  and (?4 = '' or lower(r.customerName) like ?4 or r.customerDoc like ?4 or cast(r.number as string) = ?5)
                order by r.createdAt desc""")
        List<Remission> search(Long tenantId, String status, Long customerId, String like, String exact, Pageable page);

        List<Remission> findByTenantIdAndSaleIdOrderByNumberAsc(Long tenantId, Long saleId);

        List<Remission> findByTenantIdAndQuoteIdAndStatusNot(Long tenantId, Long quoteId, String status);
    }

    public interface RemissionLineRepository extends JpaRepository<RemissionLine, Long> {
        List<RemissionLine> findByRemissionIdOrderBySortOrderAscIdAsc(Long remissionId);

        @Modifying
        @Query("delete from RemissionLine l where l.remissionId = ?1")
        void deleteByRemissionId(Long remissionId);
    }
}
