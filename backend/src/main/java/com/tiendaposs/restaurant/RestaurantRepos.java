package com.tiendaposs.restaurant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class RestaurantRepos {
    private RestaurantRepos() {
    }

    public interface DiningAreaRepository extends JpaRepository<DiningArea, Long> {
        List<DiningArea> findByTenantIdAndActiveTrueOrderBySortOrderAscIdAsc(Long tenantId);

        Optional<DiningArea> findByIdAndTenantId(Long id, Long tenantId);
    }

    public interface DiningTableRepository extends JpaRepository<DiningTable, Long> {
        List<DiningTable> findByTenantIdAndActiveTrueOrderBySortOrderAscIdAsc(Long tenantId);

        Optional<DiningTable> findByIdAndTenantId(Long id, Long tenantId);
    }

    public interface ModifierGroupRepository extends JpaRepository<ModifierGroup, Long> {
        List<ModifierGroup> findByTenantIdAndActiveTrueOrderBySortOrderAscIdAsc(Long tenantId);

        Optional<ModifierGroup> findByIdAndTenantId(Long id, Long tenantId);
    }

    public interface ModifierOptionRepository extends JpaRepository<ModifierOption, Long> {
        List<ModifierOption> findByTenantIdAndActiveTrueOrderBySortOrderAscIdAsc(Long tenantId);

        List<ModifierOption> findByGroupIdOrderBySortOrderAscIdAsc(Long groupId);

        List<ModifierOption> findByIdInAndTenantId(Collection<Long> ids, Long tenantId);
    }

    public interface KitchenTicketRepository extends JpaRepository<KitchenTicket, Long> {
        @Query("select k from KitchenTicket k where k.tenantId = ?1 and (k.status in ('PENDIENTE','PREPARANDO','LISTO') or k.deliveredAt > ?2) order by k.createdAt asc, k.id asc")
        List<KitchenTicket> board(Long tenantId, Instant deliveredSince);

        List<KitchenTicket> findBySaleIdOrderByIdAsc(Long saleId);

        Optional<KitchenTicket> findByIdAndTenantId(Long id, Long tenantId);

        @Modifying
        @Query("update KitchenTicket k set k.saleId = ?2, k.tableLabel = ?3 where k.saleId = ?1")
        int moveToSale(Long fromSaleId, Long toSaleId, String tableLabel);
    }
}
