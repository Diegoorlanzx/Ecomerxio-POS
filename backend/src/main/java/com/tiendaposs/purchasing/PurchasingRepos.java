package com.tiendaposs.purchasing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class PurchasingRepos {
    private PurchasingRepos() {
    }

    public interface SupplierRepository extends JpaRepository<Supplier, Long> {
        List<Supplier> findByTenantIdOrderByNameAsc(Long tenantId);

        Optional<Supplier> findByIdAndTenantId(Long id, Long tenantId);

        @Query("select count(s) > 0 from Supplier s where s.tenantId = ?1 and lower(s.name) = lower(?2) and (?3 is null or s.id <> ?3)")
        boolean nameTaken(Long tenantId, String name, Long excludeId);
    }

    public interface SupplierProductRepository extends JpaRepository<SupplierProduct, Long> {
        List<SupplierProduct> findBySupplierId(Long supplierId);

        List<SupplierProduct> findByProductId(Long productId);

        List<SupplierProduct> findByTenantId(Long tenantId);

        Optional<SupplierProduct> findBySupplierIdAndProductId(Long supplierId, Long productId);

        Optional<SupplierProduct> findByIdAndTenantId(Long id, Long tenantId);
    }

    public interface SupplierInvoiceRepository extends JpaRepository<SupplierInvoice, Long> {
        Optional<SupplierInvoice> findByTenantIdAndCufe(Long tenantId, String cufe);

        Optional<SupplierInvoice> findByTenantIdAndSupplierIdAndInvoiceNumber(Long tenantId, Long supplierId, String invoiceNumber);

        Optional<SupplierInvoice> findByIdAndTenantId(Long id, Long tenantId);

        Optional<SupplierInvoice> findByReceiptId(Long receiptId);

        /** Sin el XML (proyección cerrada: solo se leen estas columnas). */
        List<SupplierInvoiceRow> findTop200ByTenantIdOrderByCreatedAtDescIdDesc(Long tenantId);
    }

    public interface SupplierInvoiceRow {
        Long getId();

        Long getSupplierId();

        Long getReceiptId();

        Long getOrderId();

        String getInvoiceNumber();

        String getCufe();

        java.time.LocalDate getIssueDate();

        java.time.LocalDate getDueDate();

        BigDecimal getSubtotal();

        BigDecimal getTaxTotal();

        BigDecimal getTotal();

        String getFileName();

        String getImportedBy();

        java.time.Instant getCreatedAt();
    }

    public interface SupplierPriceHistoryRepository extends JpaRepository<SupplierPriceHistory, Long> {
        List<SupplierPriceHistory> findTop200BySupplierIdOrderByCreatedAtDesc(Long supplierId);
    }

    public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {
        Optional<PurchaseOrder> findByIdAndTenantId(Long id, Long tenantId);

        @Query("""
                select o from PurchaseOrder o where o.tenantId = :tenantId
                  and (:status is null or o.status = :status)
                  and (:supplierId is null or o.supplierId = :supplierId)
                order by o.createdAt desc""")
        List<PurchaseOrder> search(Long tenantId, String status, Long supplierId);

        List<PurchaseOrder> findByTenantIdAndStatusIn(Long tenantId, Collection<String> statuses);

        List<PurchaseOrder> findBySupplierIdOrderByCreatedAtDesc(Long supplierId);

        @Query("select coalesce(sum(o.estimatedTotal), 0) from PurchaseOrder o where o.tenantId = ?1 and o.status in ('APROBADA','PARCIAL','RECIBIDA') and o.approvedAt >= ?2")
        BigDecimal approvedSince(Long tenantId, Instant since);
    }

    public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, Long> {
        List<PurchaseOrderLine> findByOrderIdOrderByIdAsc(Long orderId);

        List<PurchaseOrderLine> findByOrderIdIn(Collection<Long> orderIds);

        void deleteByOrderId(Long orderId);
    }

    public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, Long> {
        List<GoodsReceipt> findBySupplierIdOrderByReceivedAtDesc(Long supplierId);

        List<GoodsReceipt> findByOrderIdOrderByReceivedAtAsc(Long orderId);

        List<GoodsReceipt> findByTenantIdOrderByReceivedAtDesc(Long tenantId);

        Optional<GoodsReceipt> findByIdAndTenantId(Long id, Long tenantId);

        @Query("select coalesce(sum(r.total - r.paidAmount), 0) from GoodsReceipt r where r.supplierId = ?1")
        BigDecimal balance(Long supplierId);
    }

    public interface GoodsReceiptLineRepository extends JpaRepository<GoodsReceiptLine, Long> {
        List<GoodsReceiptLine> findByReceiptIdIn(Collection<Long> receiptIds);
    }

    public interface SupplierReturnRepository extends JpaRepository<SupplierReturn, Long> {
        List<SupplierReturn> findBySupplierIdOrderByCreatedAtDesc(Long supplierId);
    }

    public interface InventoryCountRepository extends JpaRepository<InventoryCount, Long> {
        List<InventoryCount> findByTenantIdOrderByCreatedAtDesc(Long tenantId);

        Optional<InventoryCount> findByIdAndTenantId(Long id, Long tenantId);
    }

    public interface InventoryCountLineRepository extends JpaRepository<InventoryCountLine, Long> {
        List<InventoryCountLine> findByCountIdOrderByIdAsc(Long countId);

        Optional<InventoryCountLine> findByCountIdAndProductId(Long countId, Long productId);
    }
}
