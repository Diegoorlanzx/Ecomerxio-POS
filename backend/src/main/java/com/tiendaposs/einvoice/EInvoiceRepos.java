package com.tiendaposs.einvoice;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class EInvoiceRepos {
    private EInvoiceRepos() {
    }

    public interface DocumentRepository extends JpaRepository<ElectronicDocument, Long> {
        Optional<ElectronicDocument> findByIdAndTenantId(Long id, Long tenantId);

        /** El documento de venta (no las notas) de una venta. */
        @Query("select d from ElectronicDocument d where d.saleId = ?1 and d.documentType <> 'NOTA_CREDITO'")
        Optional<ElectronicDocument> findSaleDocument(Long saleId);

        List<ElectronicDocument> findByOriginalIdOrderByIdAsc(Long originalId);

        Optional<ElectronicDocument> findByReceiptId(Long receiptId);

        @Query("select d from ElectronicDocument d where d.tenantId = ?1 and (?2 is null or d.status = ?2) order by d.createdAt desc, d.id desc")
        List<ElectronicDocument> list(Long tenantId, String status, Pageable page);

        @Query("select d.status, count(d) from ElectronicDocument d where d.tenantId = ?1 group by d.status")
        List<Object[]> countByStatus(Long tenantId);

        boolean existsByTenantIdAndEnvironmentAndStatus(Long tenantId, String environment, String status);

        /** Pendientes de envío o de consulta cuya hora llegó (la cola). */
        @Query(value = """
                select id from electronic_documents
                where status in ('PENDIENTE', 'EN_PROCESO', 'DESCONOCIDO') and next_attempt_at <= ?1
                order by next_attempt_at limit ?2
                """, nativeQuery = true)
        List<Long> dueIds(Instant now, int limit);

        /** Bloquea la fila para que dos procesos no envíen el mismo documento; null si otro ya la tiene. */
        @Query(value = "select * from electronic_documents where id = ?1 for update skip locked", nativeQuery = true)
        Optional<ElectronicDocument> lockForProcessing(Long id);

        List<ElectronicDocument> findByTenantIdAndStatusIn(Long tenantId, Collection<String> statuses);
    }

    public interface EventRepository extends JpaRepository<ElectronicDocumentEvent, Long> {
        List<ElectronicDocumentEvent> findByDocumentIdOrderByIdAsc(Long documentId);
    }
}
