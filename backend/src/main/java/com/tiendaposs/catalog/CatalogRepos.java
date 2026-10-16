package com.tiendaposs.catalog;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class CatalogRepos {
    private CatalogRepos() {
    }

    public interface CategoryRepository extends JpaRepository<Category, Long> {
        List<Category> findByTenantIdOrderBySortOrderAscNameAsc(Long tenantId);

        Optional<Category> findByIdAndTenantId(Long id, Long tenantId);

        @Query("select c from Category c where c.tenantId = ?1 and lower(c.name) = lower(?2) and ((?3 is null and c.parentId is null) or c.parentId = ?3)")
        Optional<Category> findByName(Long tenantId, String name, Long parentId);

        @Query("select count(c) from Category c where c.parentId = ?1")
        long countChildren(Long id);
    }

    public interface ProductRepository extends JpaRepository<Product, Long> {
        Optional<Product> findByIdAndTenantId(Long id, Long tenantId);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select p from Product p where p.id = ?1 and p.tenantId = ?2")
        Optional<Product> lockById(Long id, Long tenantId);

        @Query("select p from Product p where p.tenantId = ?1 and lower(p.sku) = lower(?2)")
        Optional<Product> findBySku(Long tenantId, String sku);

        @Query("select count(p) > 0 from Product p where p.tenantId = ?1 and lower(p.sku) = lower(?2) and (?3 is null or p.id <> ?3)")
        boolean skuTaken(Long tenantId, String sku, Long excludeId);

        @Query("""
                select p from Product p where p.tenantId = :tenantId
                  and (:allCategories = true or p.categoryId in :categoryIds)
                  and (:status is null or p.status = :status)
                  and (:q is null or cast(function('translate', lower(p.name), 'áéíóúüñ', 'aeiouun') as String) like :q or lower(p.sku) like :q
                       or cast(function('translate', lower(coalesce(p.brand, '')), 'áéíóúüñ', 'aeiouun') as String) like :q
                       or exists (select 1 from Presentation pr where pr.productId = p.id and pr.barcode = :code)
                       or exists (select 1 from Product c where c.parentId = p.id and (lower(c.sku) like :q
                           or exists (select 1 from Presentation cp where cp.productId = c.id and cp.barcode = :code))))
                  and (:level is null or (:level = 'TOP' and p.parentId is null) or (:level = 'ITEMS' and p.productType <> 'PADRE')
                       or (:level = 'STOCK' and p.productType <> 'PADRE' and p.productType <> 'COMBO'))
                """)
        Page<Product> search(Long tenantId, boolean allCategories, Collection<Long> categoryIds, String status, String q, String code,
                             String level, Pageable pageable);

        List<Product> findByParentIdOrderByIdAsc(Long parentId);

        List<Product> findByParentIdIn(Collection<Long> parentIds);

        @Query("select p from Product p where p.tenantId = ?1 and p.plu = ?2")
        Optional<Product> findByPlu(Long tenantId, String plu);

        List<Product> findByTenantIdAndStatusOrderByNameAsc(Long tenantId, String status);

        List<Product> findByTenantIdOrderByNameAsc(Long tenantId);

        long countByCategoryId(Long categoryId);
    }

    public interface ComboComponentRepository extends JpaRepository<ComboComponent, Long> {
        List<ComboComponent> findByComboIdOrderBySortOrderAscIdAsc(Long comboId);

        List<ComboComponent> findByComboIdIn(Collection<Long> comboIds);

        List<ComboComponent> findByTenantId(Long tenantId);

        @Query("select count(c) > 0 from ComboComponent c where c.componentId = ?1")
        boolean usedAsComponent(Long productId);

        void deleteByComboId(Long comboId);
    }

    public interface PresentationRepository extends JpaRepository<Presentation, Long> {
        List<Presentation> findByProductIdOrderBySortOrderAscIdAsc(Long productId);

        List<Presentation> findByProductIdIn(Collection<Long> productIds);

        List<Presentation> findByTenantId(Long tenantId);

        Optional<Presentation> findByIdAndTenantId(Long id, Long tenantId);

        @Query("select p from Presentation p where p.tenantId = ?1 and p.barcode = ?2")
        Optional<Presentation> findByBarcode(Long tenantId, String barcode);
    }

    public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {
        List<PriceHistory> findTop100ByProductIdAndTenantIdOrderByCreatedAtDesc(Long productId, Long tenantId);
    }
}
