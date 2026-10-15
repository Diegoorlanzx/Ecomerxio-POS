package com.tiendaposs.notifications;

import com.tiendaposs.notifications.NotificationService.*;
import com.tiendaposs.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /** Bandeja del usuario. Si la última revisión fue hace más de 2 minutos, revisa el negocio primero. */
    @GetMapping
    public Inbox inbox(@RequestParam(defaultValue = "false") boolean onlyUnread, @RequestParam(defaultValue = "100") int limit) {
        service.scanIfStale();
        return service.inbox(onlyUnread, limit);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unread() {
        service.scanIfStale();
        return Map.of("unread", service.inbox(true, 1).unread());
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable Long id) {
        service.markRead(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public Map<String, Integer> readAll() {
        return Map.of("marked", service.markAllRead());
    }

    @PostMapping("/scan")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public Map<String, Integer> scan() {
        return Map.of("created", service.scanNow(TenantContext.require()));
    }

    @GetMapping("/deliveries")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public List<DeliveryDto> deliveries(@RequestParam(required = false) Long notificationId) {
        return service.deliveries(notificationId);
    }

    @GetMapping("/rules")
    @PreAuthorize("hasAnyAuthority('CONFIG_EDITAR','ALERTAS_VER')")
    public List<RuleDto> rules() {
        return service.rules();
    }

    @PutMapping("/rules")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public List<RuleDto> saveRules(@RequestBody List<@Valid RuleRequest> rules) {
        return service.saveRules(rules);
    }

    @GetMapping("/channels")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public ChannelsDto channels() {
        return service.channels();
    }

    @PutMapping("/channels")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public ChannelsDto saveChannels(@Valid @RequestBody ChannelsRequest r) {
        return service.saveChannels(r);
    }

    public record TestRequest(@NotBlank @Pattern(regexp = "EMAIL|WEBHOOK|WHATSAPP", message = "canal no válido") String channel) {
    }

    @PostMapping("/channels/test")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public Map<String, String> test(@Valid @RequestBody TestRequest r) {
        return service.test(r.channel());
    }
}
