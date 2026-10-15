package com.tiendaposs.inventory;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogDtos.PageDto;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventory;
    private final ProductRepository products;
    private final AuditService audit;

    public InventoryController(InventoryService inventory, ProductRepository products, AuditService audit) {
        this.inventory = inventory;
        this.products = products;
        this.audit = audit;
    }

    /**
     * ENTRADA suma, SALIDA resta, DANADO resta marcando mercancía dañada/vencida, CONTEO fija la
     * existencia a lo contado (el sistema calcula la diferencia). Siempre con motivo.
     */
    public record AdjustRequest(
            @NotNull Long productId,
            @NotBlank @Pattern(regexp = "ENTRADA|SALIDA|DANADO|CONTEO", message = "tipo no válido") String mode,
            @NotNull @DecimalMin(value = "0", message = "no puede ser negativa") BigDecimal quantity,
            @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal unitCost,
            @NotBlank(message = "explica el motivo del ajuste") @Size(max = 300, message = "máximo 300 caracteres") String reason) {
    }

    public record MovementDto(Long id, Long productId, String productName, String type, BigDecimal quantity,
                              BigDecimal stockAfter, BigDecimal unitCost, String referenceType, Long referenceId,
                              String reason, String userName, Instant createdAt) {
    }

    @PostMapping("/adjust")
    @Transactional
    @PreAuthorize("hasAuthority('INVENTARIO_AJUSTAR')")
    public MovementDto adjust(@Valid @RequestBody AdjustRequest r) {
        Long t = TenantContext.require();
        Product p = products.lockById(r.productId(), t).orElseThrow(() -> ApiException.notFound("Producto no encontrado"));
        if (!p.trackStock) throw ApiException.badRequest("Este producto no controla stock");
        BigDecimal delta = switch (r.mode()) {
            case "ENTRADA" -> r.quantity();
            case "SALIDA", "DANADO" -> r.quantity().negate();
            default -> r.quantity().subtract(p.stock);
        };
        if (delta.signum() == 0) throw ApiException.badRequest("El conteo coincide con el sistema: no hay nada que ajustar");
        BigDecimal cost = r.unitCost();
        if (cost != null && !CurrentUser.get().can(Permission.COSTOS_VER)) cost = null;
        String type = switch (r.mode()) {
            case "DANADO" -> InventoryService.DANADO;
            default -> delta.signum() > 0 ? InventoryService.AJUSTE_ENTRADA : InventoryService.AJUSTE_SALIDA;
        };
        BigDecimal before = p.stock;
        StockMovement m = inventory.applyLocked(p, new InventoryService.Change(p.id, delta, type, "AJUSTE", null,
                r.reason().trim(), delta.signum() > 0 ? cost : null, false));
        audit.sensitive("AJUSTE_INVENTARIO", "PRODUCT", p.id, "Ajustó " + p.name + ": " + plain(before) + " → "
                + plain(p.stock) + " (" + (delta.signum() > 0 ? "+" : "") + plain(delta) + "). Motivo: " + r.reason().trim());
        return toDto(m, p.name);
    }

    public record CountItem(@NotNull Long productId, @NotNull @DecimalMin(value = "0", message = "no puede ser negativa") BigDecimal counted) {
    }

    public record BulkCountRequest(@NotNull @Size(min = 1, message = "agrega al menos un producto") List<@Valid CountItem> items,
                                   @NotBlank(message = "explica el motivo") @Size(max = 300) String reason) {
    }

    public record BulkCountResult(int adjusted, int unchanged) {
    }

    /** Actualización masiva de existencias: fija varias cantidades a la vez (cada diferencia queda como ajuste). */
    @PostMapping("/bulk-count")
    @Transactional
    @PreAuthorize("hasAuthority('INVENTARIO_AJUSTAR')")
    public BulkCountResult bulkCount(@Valid @RequestBody BulkCountRequest r) {
        Long t = TenantContext.require();
        int adjusted = 0, unchanged = 0;
        for (CountItem it : r.items()) {
            Product p = products.lockById(it.productId(), t).orElseThrow(() -> ApiException.notFound("Producto no encontrado"));
            if (!p.trackStock) continue;
            BigDecimal delta = it.counted().subtract(p.stock);
            if (delta.signum() == 0) {
                unchanged++;
                continue;
            }
            inventory.applyLocked(p, new InventoryService.Change(p.id, delta,
                    delta.signum() > 0 ? InventoryService.AJUSTE_ENTRADA : InventoryService.AJUSTE_SALIDA, "AJUSTE_MASIVO", null,
                    r.reason().trim(), null, false));
            adjusted++;
        }
        audit.sensitive("AJUSTE_MASIVO", "PRODUCT", null, "Actualizó existencias de " + adjusted + " productos. Motivo: " + r.reason().trim());
        return new BulkCountResult(adjusted, unchanged);
    }

    @GetMapping("/movements")
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('INVENTARIO_VER')")
    public PageDto<MovementDto> movements(@RequestParam(required = false) Long productId,
                                          @RequestParam(required = false) String type,
                                          @RequestParam(required = false) Long userId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        Long t = TenantContext.require();
        var res = inventory.search(t, productId, type == null || type.isBlank() ? null : type, userId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
        Map<Long, String> names = products.findAllById(res.getContent().stream().map(m -> m.productId).distinct().toList())
                .stream().collect(Collectors.toMap(p -> p.id, p -> p.name));
        boolean costs = CurrentUser.get().can(Permission.COSTOS_VER);
        return new PageDto<>(res.getContent().stream().map(m -> {
            MovementDto d = toDto(m, names.get(m.productId));
            return costs ? d : new MovementDto(d.id(), d.productId(), d.productName(), d.type(), d.quantity(), d.stockAfter(),
                    null, d.referenceType(), d.referenceId(), d.reason(), d.userName(), d.createdAt());
        }).toList(), res.getTotalElements(), res.getNumber(), res.getTotalPages());
    }

    private static MovementDto toDto(StockMovement m, String name) {
        return new MovementDto(m.id, m.productId, name, m.type, m.quantity, m.stockAfter, m.unitCost, m.referenceType,
                m.referenceId, m.reason, m.userName, m.createdAt);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
