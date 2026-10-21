package com.tiendaposs.quotes;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class QuoteRepos {
    private QuoteRepos() {
    }

    public interface QuoteRepository extends JpaRepository<Quote, Long> {
        Optional<Quote> findByIdAndTenantId(Long id, Long tenantId);

        Optional<Quote> findByPublicToken(String token);

        @Query("""
                select q from Quote q where q.tenantId = ?1 and q.kind = ?2
                  and (?3 = '' or lower(q.customerName) like ?3 or q.customerDoc like ?3 or cast(q.number as string) = ?4)
                order by q.createdAt desc""")
        List<Quote> search(Long tenantId, String kind, String like, String exact, Pageable page);

        List<Quote> findBySourceIdAndKind(Long sourceId, String kind);

        /** Posibles duplicados: otra prefactura viva para el mismo documento y el mismo total. */
        @Query("""
                select q from Quote q where q.tenantId = ?1 and q.kind = 'PREFACTURA' and q.id <> ?2 and q.customerDoc = ?3
                  and q.total = ?4 and q.status <> 'ANULADA' and q.createdAt > ?5""")
        List<Quote> sameCustomerAndTotal(Long tenantId, Long id, String doc, BigDecimal total, Instant since);
    }

    public interface QuoteLineRepository extends JpaRepository<QuoteLine, Long> {
        List<QuoteLine> findByQuoteIdOrderBySortOrderAscIdAsc(Long quoteId);

        @Modifying
        @Query("delete from QuoteLine l where l.quoteId = ?1")
        void deleteByQuoteId(Long quoteId);
    }
}
