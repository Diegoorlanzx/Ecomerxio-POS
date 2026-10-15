package com.tiendaposs.notifications;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Entidades y repositorios de notificaciones. */
public final class NotificationEntities {
    private NotificationEntities() {
    }

    public interface NotificationRepository extends JpaRepository<Notification, Long> {
        boolean existsByTenantIdAndDedupKey(Long tenantId, String dedupKey);

        Optional<Notification> findByIdAndTenantId(Long id, Long tenantId);

        @Query("select n from Notification n where n.tenantId = ?1 and n.createdAt >= ?2 order by n.createdAt desc, n.id desc")
        List<Notification> recent(Long tenantId, Instant since);
    }

    public interface DeliveryRepository extends JpaRepository<Delivery, Long> {
        List<Delivery> findByNotificationIdOrderByIdAsc(Long notificationId);

        @Query("select d from Delivery d where d.tenantId = ?1 order by d.id desc limit 50")
        List<Delivery> latest(Long tenantId);
    }

    public interface RuleRepository extends JpaRepository<Rule, RuleKey> {
        List<Rule> findByTenantId(Long tenantId);
    }

    public interface ChannelsRepository extends JpaRepository<Channels, Long> {
        @Modifying
        @Query(value = "insert into notification_channels (tenant_id) values (?1) on conflict do nothing", nativeQuery = true)
        void ensure(Long tenantId);
    }
}
