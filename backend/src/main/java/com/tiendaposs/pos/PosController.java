package com.tiendaposs.pos;

import com.tiendaposs.catalog.CatalogDtos.PageDto;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.pos.PosCrudService.*;
import com.tiendaposs.pos.SaleDtos.*;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.settings.StoreSettings;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.tenant.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@RestController
@RequestMapping("/api/pos")
public class PosController {

    private final SaleService sales;
    private final ReturnService returns;
    private final ApprovalService approvals;
    private final PosCrudService crud;
    private final SettingsService settings;
    private final TenantRepository tenants;

    private final com.tiendaposs.online.OnlineRepos.OnlineStoreRepository onlineStores;

    public PosController(SaleService sales, ReturnService returns, ApprovalService approvals, PosCrudService crud,
                         SettingsService settings, TenantRepository tenants,
                         com.tiendaposs.online.OnlineRepos.OnlineStoreRepository onlineStores) {
        this.onlineStores = onlineStores;
        this.sales = sales;
        this.returns = returns;
        this.approvals = approvals;
        this.crud = crud;
        this.settings = settings;
        this.tenants = tenants;
    }

    // ---------- Cobro directo y sin internet ----------

    @PostMapping("/sales")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public SaleDto checkout(@Valid @RequestBody CheckoutRequest r) {
        return sales.checkout(r);
    }

    @GetMapping("/sales")
    public PageDto<SaleSummaryDto> history(@RequestParam(required = false) Long userId,
                                           @RequestParam(required = false) Long shiftId,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) Long number,
                                           @RequestParam(required = false) BigDecimal minTotal,
                                           @RequestParam(required = false) BigDecimal maxTotal,
                                           @RequestParam(defaultValue = "false") boolean onlyReview,
                                           @RequestParam(required = false) LocalDate from,
                                           @RequestParam(required = false) LocalDate to,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "30") int size) {
        ZoneId zone = zone();
        Instant f = from == null ? Instant.EPOCH : from.atStartOfDay(zone).toInstant();
        Instant t = to == null ? Instant.now().plusSeconds(3600) : to.plusDays(1).atStartOfDay(zone).toInstant();
        return sales.history(userId, shiftId, blank(status), number, minTotal, maxTotal, onlyReview, f, t, Math.max(page, 0),
                Math.min(Math.max(size, 1), 100));
    }

    @GetMapping("/sales/{id}")
    public SaleDto sale(@PathVariable Long id) {
        return sales.get(id);
    }

    @GetMapping("/sales/number/{number}")
    public SaleDto saleByNumber(@PathVariable Long number) {
        return sales.byNumber(number);
    }

    @PostMapping("/sales/{id}/void")
    public SaleDto voidSale(@PathVariable Long id, @Valid @RequestBody VoidRequest r) {
        return sales.voidSale(id, r);
    }

    @PostMapping("/sales/{id}/returns")
    public SaleDto createReturn(@PathVariable Long id, @Valid @RequestBody ReturnRequest r) {
        return returns.create(id, r);
    }

    @PostMapping("/sales/{id}/reviewed")
    public SaleDto reviewed(@PathVariable Long id) {
        return sales.markReviewed(id);
    }

    /** Todo lo necesario para imprimir el comprobante. */
    public record ReceiptDto(SaleDto sale, String storeName, String legalName, String taxId, String taxRegime, String address,
                             String city, String phone, String logo, String header, String footer, int widthMm,
                             boolean showTax, String currency, String onlineSlug) {
    }

    @GetMapping("/sales/{id}/receipt")
    public ReceiptDto receipt(@PathVariable Long id) {
        SaleDto s = sales.get(id);
        Tenant t = tenants.findById(TenantContext.require()).orElseThrow();
        StoreSettings st = settings.current();
        return new ReceiptDto(s, t.getName(), t.getLegalName(), t.getTaxId(), t.getTaxRegime(), t.getAddress(), t.getCity(),
                t.getPhone(), t.getLogo(), st.receiptHeader, st.receiptFooter, st.receiptWidthMm, st.receiptShowTax, t.getCurrency(),
                onlineStores.findById(t.getId()).filter(o -> o.enabled && st.hasModule("TIENDA_ONLINE")).map(o -> o.slug).orElse(null));
    }

    // ---------- Cuentas abiertas ----------

    @GetMapping("/accounts")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public List<AccountSummaryDto> accounts() {
        return sales.listAccounts();
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public SaleDto openAccount(@Valid @RequestBody AccountRequest r) {
        return sales.openAccount(r);
    }

    @GetMapping("/accounts/{id}")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public SaleDto account(@PathVariable Long id) {
        return sales.get(id);
    }

    @PutMapping("/accounts/{id}")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public SaleDto updateAccount(@PathVariable Long id, @Valid @RequestBody AccountRequest r) {
        return sales.updateAccount(id, r);
    }

    @PostMapping("/accounts/{id}/lines")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public SaleDto addLine(@PathVariable Long id, @Valid @RequestBody AddLineRequest r) {
        return sales.addAccountLine(id, r);
    }

    @PatchMapping("/accounts/{id}/lines/{lineId}")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public SaleDto setQty(@PathVariable Long id, @PathVariable Long lineId, @Valid @RequestBody QuantityRequest r) {
        return sales.setAccountLineQty(id, lineId, r.quantity());
    }

    @DeleteMapping("/accounts/{id}/lines/{lineId}")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public SaleDto removeLine(@PathVariable Long id, @PathVariable Long lineId) {
        return sales.removeAccountLine(id, lineId);
    }

    @PostMapping("/accounts/{id}/checkout")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public SaleDto checkoutAccount(@PathVariable Long id, @Valid @RequestBody AccountCheckoutRequest r) {
        return sales.checkoutAccount(id, r);
    }

    public record CancelAccountRequest(String reason, List<String> approvals) {
    }

    @PostMapping("/accounts/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public void cancelAccount(@PathVariable Long id, @RequestBody(required = false) CancelAccountRequest r) {
        sales.cancelAccount(id, r == null ? null : r.reason(), r == null ? null : r.approvals());
    }

    // ---------- Ventas en espera ----------

    @GetMapping("/holds")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public List<SaleDto> holds() {
        return sales.listHolds();
    }

    @PostMapping("/holds")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public SaleDto hold(@Valid @RequestBody HoldRequest r) {
        return sales.hold(r);
    }

    @PostMapping("/holds/{id}/recover")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public SaleDto recover(@PathVariable Long id) {
        return sales.recoverHold(id);
    }

    // ---------- Autorizaciones de supervisor ----------

    @GetMapping("/approvers")
    public List<ApprovalService.Approver> approvers(@RequestParam String permission) {
        return approvals.approvers(permission(permission));
    }

    public record ApproveRequest(@NotNull Long approverId, @NotBlank(message = "escribe el PIN") String secret, @NotBlank String permission) {
    }

    @PostMapping("/approvals")
    public ApprovalService.ApprovalResult approve(@Valid @RequestBody ApproveRequest r) {
        return approvals.approve(r.approverId(), r.secret(), permission(r.permission()));
    }

    // ---------- Clientes ----------

    @GetMapping("/customers")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','FIADO_GESTIONAR','REPORTES_VER')")
    public List<CustomerDto> customers(@RequestParam(required = false) String q) {
        return crud.searchCustomers(q);
    }

    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('POS_VENDER','FIADO_GESTIONAR')")
    public CustomerDto createCustomer(@Valid @RequestBody CustomerUpsert r) {
        return crud.saveCustomer(null, r);
    }

    @PutMapping("/customers/{id}")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','FIADO_GESTIONAR')")
    public CustomerDto updateCustomer(@PathVariable Long id, @Valid @RequestBody CustomerUpsert r) {
        return crud.saveCustomer(id, r);
    }

    // ---------- Promociones ----------

    @GetMapping("/promotions")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','PRODUCTOS_VER')")
    public List<PromotionDto> promotions(@RequestParam(defaultValue = "false") boolean onlyActive) {
        return crud.listPromotions(onlyActive);
    }

    @PostMapping("/promotions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PRODUCTOS_PRECIOS')")
    public PromotionDto createPromotion(@Valid @RequestBody PromotionRequest r) {
        return crud.savePromotion(null, r);
    }

    @PutMapping("/promotions/{id}")
    @PreAuthorize("hasAuthority('PRODUCTOS_PRECIOS')")
    public PromotionDto updatePromotion(@PathVariable Long id, @Valid @RequestBody PromotionRequest r) {
        return crud.savePromotion(id, r);
    }

    @DeleteMapping("/promotions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PRODUCTOS_PRECIOS')")
    public void deletePromotion(@PathVariable Long id) {
        crud.deletePromotion(id);
    }

    private ZoneId zone() {
        return ZoneId.of(tenants.findById(TenantContext.require()).map(Tenant::getTimezone).orElse("America/Bogota"));
    }

    private static Permission permission(String code) {
        try {
            return Permission.valueOf(code);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Permiso desconocido");
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
