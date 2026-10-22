package com.tiendaposs.distribution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class DistributionRepos {
    private DistributionRepos() {
    }

    public interface PriceListRepository extends JpaRepository<PriceList, Long> {
        List<PriceList> findByTenantIdOrderByNameAsc(Long tenantId);

        Optional<PriceList> findByIdAndTenantId(Long id, Long tenantId);
    }

    public interface PriceListItemRepository extends JpaRepository<PriceListItem, Long> {
        List<PriceListItem> findByListIdOrderByPresentationIdAscMinQtyAsc(Long listId);

        List<PriceListItem> findByTenantId(Long tenantId);

        void deleteByListId(Long listId);
    }

    public interface SalesRouteRepository extends JpaRepository<SalesRoute, Long> {
        List<SalesRoute> findByTenantIdOrderByNameAsc(Long tenantId);

        Optional<SalesRoute> findByIdAndTenantId(Long id, Long tenantId);
    }

    public interface PresaleOrderRepository extends JpaRepository<PresaleOrder, Long> {
        Optional<PresaleOrder> findByIdAndTenantId(Long id, Long tenantId);

        Optional<PresaleOrder> findByTenantIdAndClientRef(Long tenantId, String clientRef);

        List<PresaleOrder> findByTenantIdAndStatusInOrderByTakenAtAsc(Long tenantId, Collection<String> status);

        List<PresaleOrder> findByDispatchIdOrderByIdAsc(Long dispatchId);

        List<PresaleOrder> findByTenantIdOrderByTakenAtDesc(Long tenantId);
    }

    public interface PresaleOrderLineRepository extends JpaRepository<PresaleOrderLine, Long> {
        List<PresaleOrderLine> findByOrderIdOrderByIdAsc(Long orderId);

        List<PresaleOrderLine> findByOrderIdIn(Collection<Long> orderIds);
    }

    public interface RouteVisitRepository extends JpaRepository<RouteVisit, Long> {
        Optional<RouteVisit> findByTenantIdAndClientRef(Long tenantId, String clientRef);

        List<RouteVisit> findByTenantIdAndVisitDateBetween(Long tenantId, LocalDate from, LocalDate to);
    }

    public interface DispatchRepository extends JpaRepository<Dispatch, Long> {
        Optional<Dispatch> findByIdAndTenantId(Long id, Long tenantId);

        List<Dispatch> findByTenantIdOrderByCreatedAtDesc(Long tenantId);
    }
}
