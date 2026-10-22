package com.tiendaposs.services;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class ServicesRepos {
    private ServicesRepos() {
    }

    public interface StaffRepository extends JpaRepository<StaffMember, Long> {
        List<StaffMember> findByTenantIdOrderBySortOrderAscIdAsc(Long tenantId);

        Optional<StaffMember> findByIdAndTenantId(Long id, Long tenantId);
    }

    public interface StaffHourRepository extends JpaRepository<StaffHour, Long> {
        List<StaffHour> findByStaffIdOrderByWeekdayAscStartTimeAsc(Long staffId);

        List<StaffHour> findByTenantId(Long tenantId);

        void deleteByStaffId(Long staffId);
    }

    public interface AppointmentRepository extends JpaRepository<Appointment, Long> {
        Optional<Appointment> findByIdAndTenantId(Long id, Long tenantId);

        @Query("select a from Appointment a where a.tenantId = ?1 and a.startAt < ?3 and a.endAt > ?2 order by a.startAt asc, a.id asc")
        List<Appointment> between(Long tenantId, Instant from, Instant to);

        @Query("""
                select a from Appointment a where a.staffId = ?1 and a.status not in ('CANCELADA','NO_ASISTIO')
                and a.startAt < ?3 and a.endAt > ?2 order by a.startAt asc""")
        List<Appointment> busy(Long staffId, Instant from, Instant to);

        Optional<Appointment> findBySaleId(Long saleId);
    }

    public interface StaffPayoutRepository extends JpaRepository<StaffPayout, Long> {
        List<StaffPayout> findByTenantIdOrderByCreatedAtDesc(Long tenantId);
    }

    public interface ServiceOrderRepository extends JpaRepository<ServiceOrder, Long> {
        Optional<ServiceOrder> findByIdAndTenantId(Long id, Long tenantId);

        List<ServiceOrder> findByTenantIdOrderByCreatedAtDesc(Long tenantId);
    }

    public interface ServiceOrderPartRepository extends JpaRepository<ServiceOrderPart, Long> {
        List<ServiceOrderPart> findByOrderIdOrderByIdAsc(Long orderId);
    }

    public interface ServiceOrderDepositRepository extends JpaRepository<ServiceOrderDeposit, Long> {
        List<ServiceOrderDeposit> findByOrderIdOrderByIdAsc(Long orderId);
    }

    public interface ServiceOrderEventRepository extends JpaRepository<ServiceOrderEvent, Long> {
        List<ServiceOrderEvent> findByOrderIdOrderByIdAsc(Long orderId);
    }
}
