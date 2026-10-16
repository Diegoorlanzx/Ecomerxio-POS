package com.tiendaposs.online;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class OnlineRepos {
    private OnlineRepos() {
    }

    public interface OnlineStoreRepository extends JpaRepository<OnlineStore, Long> {
        @Query("select s from OnlineStore s where lower(s.slug) = lower(?1)")
        Optional<OnlineStore> findBySlug(String slug);

        @Query("select count(s) > 0 from OnlineStore s where lower(s.slug) = lower(?1) and s.tenantId <> ?2")
        boolean slugTaken(String slug, Long tenantId);
    }

    public interface DeliveryZoneRepository extends JpaRepository<DeliveryZone, Long> {
        List<DeliveryZone> findByTenantIdOrderBySortOrderAscIdAsc(Long tenantId);

        Optional<DeliveryZone> findByIdAndTenantId(Long id, Long tenantId);
    }

    public interface OnlineOrderRepository extends JpaRepository<OnlineOrder, Long> {
        Optional<OnlineOrder> findByToken(String token);

        Optional<OnlineOrder> findByIdAndTenantId(Long id, Long tenantId);

        List<OnlineOrder> findByTenantIdAndStatusInOrderByCreatedAtAsc(Long tenantId, Collection<String> statuses);

        List<OnlineOrder> findTop100ByTenantIdAndStatusInOrderByCreatedAtDesc(Long tenantId, Collection<String> statuses);

        long countByTenantIdAndStatus(Long tenantId, String status);

        @Query("select o from OnlineOrder o where o.tenantId = ?1 and o.phone = ?2 and o.createdAt > ?3")
        List<OnlineOrder> recentByPhone(Long tenantId, String phone, java.time.Instant since);
    }

    public interface OnlineOrderLineRepository extends JpaRepository<OnlineOrderLine, Long> {
        List<OnlineOrderLine> findByOrderIdOrderBySortOrderAscIdAsc(Long orderId);
    }

    public interface OnlineReservationRepository extends JpaRepository<OnlineReservation, Long> {
        List<OnlineReservation> findByOrderId(Long orderId);

        void deleteByOrderId(Long orderId);
    }
}
