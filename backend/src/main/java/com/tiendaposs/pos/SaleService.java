package com.tiendaposs.pos;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogRepos.CategoryRepository;
import com.tiendaposs.catalog.CatalogRepos.PresentationRepository;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Category;
import com.tiendaposs.catalog.Presentation;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Counters;
import com.tiendaposs.common.Money;
import com.tiendaposs.credit.CreditService;
import com.tiendaposs.inventory.InventoryService;
import com.tiendaposs.payments.PaymentService;
import com.tiendaposs.pos.PosRepos.*;
import com.tiendaposs.pos.SaleDtos.*;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.settings.StoreSettings;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.tenant.TenantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Ventas del POS: cobro directo, cuentas abiertas (hasta el límite de la tienda; el stock se
 * descuenta AL COBRAR), ventas en espera, anulaciones y venta sin internet sincronizada.
 */
@Service
public class SaleService {

    public static final String ABIERTA = "ABIERTA", EN_ESPERA = "EN_ESPERA", PAGADA = "PAGADA", ANULADA = "ANULADA";

    private final SaleRepository sales;
    private final SaleLineRepository lines;
    private final PaymentRepository payments;
    private final SaleReturnRepository returns;
    private final SaleReturnLineRepository returnLines;
    private final ProductRepository products;
    private final PresentationRepository presentations;
    private final CategoryRepository categories;
    private final PromotionRepository promotions;
    private final CustomerRepository customers;
    private final ShiftRepository shifts;
    private final ShiftService shiftService;
    private final InventoryService inventory;
    private final SettingsService settings;
    private final ApprovalService approvals;
    private final TenantRepository tenants;
    private final Counters counters;
    private final AuditService audit;
    private final PaymentService paymentService;
    private final CreditService creditService;
    private final com.tiendaposs.catalog.AdvancedCatalogService advanced;
    private final com.tiendaposs.serials.SerialService serialService;
    private final SaleLineComponentRepository lineComponents;

    /** Fase 13B: documento electrónico de cada venta y nota crédito de cada anulación. */
    @org.springframework.beans.factory.annotation.Autowired
    private com.tiendaposs.einvoice.EInvoiceService einvoice;

    /** Fase 14: comprobante contable de cada venta y reversa de cada anulación. */
    @org.springframework.beans.factory.annotation.Autowired
    private com.tiendaposs.accounting.AccountingService accounting;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.tiendaposs.restaurant.KitchenService kitchen;

    public SaleService(SaleRepository sales, SaleLineRepository lines, PaymentRepository payments,
                       SaleReturnRepository returns, SaleReturnLineRepository returnLines, ProductRepository products,
                       PresentationRepository presentations, CategoryRepository categories, PromotionRepository promotions,
                       CustomerRepository customers, ShiftRepository shifts, ShiftService shiftService,
                       InventoryService inventory, SettingsService settings, ApprovalService approvals,
                       TenantRepository tenants, Counters counters, AuditService audit,
                       PaymentService paymentService, CreditService creditService,
                       com.tiendaposs.catalog.AdvancedCatalogService advanced, com.tiendaposs.serials.SerialService serialService,
                       SaleLineComponentRepository lineComponents) {
        this.advanced = advanced;
        this.serialService = serialService;
        this.lineComponents = lineComponents;
        this.sales = sales;
        this.lines = lines;
        this.payments = payments;
        this.returns = returns;
        this.returnLines = returnLines;
        this.products = products;
        this.presentations = presentations;
        this.categories = categories;
        this.promotions = promotions;
        this.customers = customers;
        this.shifts = shifts;
        this.shiftService = shiftService;
        this.inventory = inventory;
        this.settings = settings;
        this.approvals = approvals;
        this.tenants = tenants;
        this.counters = counters;
        this.audit = audit;
        this.paymentService = paymentService;
        this.creditService = creditService;
    }

    private record Ctx(Long tenantId, AuthUser me, StoreSettings settings, int scale, LocalDate today,
                       List<Promotion> promos, Map<Long, Category> categories, ZoneId zone) {
    }

    private Ctx ctx() {
        Long t = TenantContext.require();
        Tenant tenant = tenants.findById(t).orElseThrow();
        StoreSettings s = settings.forTenant(t);
        Map<Long, Category> cats = categories.findByTenantIdOrderBySortOrderAscNameAsc(t).stream()
                .collect(Collectors.toMap(c -> c.id, c -> c));
        return new Ctx(t, CurrentUser.get(), s, Money.scale(tenant.getCurrency()),
                LocalDate.now(ZoneId.of(tenant.getTimezone())), promotions.findByTenantIdAndActiveTrue(t), cats, ZoneId.of(tenant.getTimezone()));
    }

    // =====================================================================
    // Construcción y validación de líneas
    // =====================================================================

    /** Crea las líneas (sin guardar) a partir de lo que pide la pantalla, validando precios y descuentos. */
    private List<SaleLine> buildLines(Ctx c, List<LineRequest> reqs, List<String> approvalTokens, boolean offline,
                                      Set<String> flags, List<String> authorizedBy) {
        List<SaleLine> out = new ArrayList<>();
        int order = 0;
        for (LineRequest r : reqs) {
            Presentation pr = presentations.findByIdAndTenantId(r.presentationId(), c.tenantId())
                    .orElseThrow(() -> ApiException.badRequest("Un producto de la venta ya no existe"));
            Product p = products.findByIdAndTenantId(pr.productId, c.tenantId()).orElseThrow();
            if (!offline && (!pr.active || !"ACTIVE".equals(p.status))) {
                throw ApiException.badRequest(p.name + " (" + pr.name + ") ya no se vende");
            }
            if (!p.sellable()) throw ApiException.badRequest("Elige la variante de " + p.name + " (talla, color…)");
            if (!"UNIDAD".equals(p.unit) || pr.factor.stripTrailingZeros().scale() > 0) {
                // venta por peso/volumen: cantidades decimales permitidas
            } else if (r.quantity().stripTrailingZeros().scale() > 0) {
                throw ApiException.badRequest(p.name + " se vende por unidades enteras");
            }
            SaleLine l = new SaleLine();
            l.tenantId = c.tenantId();
            l.productId = p.id;
            l.presentationId = pr.id;
            l.productName = p.name;
            l.presentationName = pr.name;
            l.factor = pr.factor;
            l.quantity = r.quantity().setScale(3, RoundingMode.HALF_UP);
            l.listPrice = pr.price;
            l.unitPrice = r.unitPrice() == null ? pr.price : r.unitPrice().setScale(2, RoundingMode.HALF_UP);
            if (offline && r.unitPrice() != null && r.unitPrice().compareTo(pr.price) != 0) {
                // Sin internet se cobró con el precio guardado en el equipo: se respeta y se marca para revisión.
                l.listPrice = r.unitPrice();
                flags.add("PRECIO_DIFERENTE");
            }
            l.discountPct = r.discountPct() == null ? BigDecimal.ZERO : r.discountPct();
            l.taxName = p.taxName;
            l.taxRate = p.taxRate;
            l.fixedTax = p.fixedTax;
            l.unitCost = advanced.unitCost(p, pr.factor);
            l.addedBy = c.me().fullName();
            if (p.trackSerials) l.serials = com.tiendaposs.serials.SerialService.join(com.tiendaposs.serials.SerialService.normalize(r.serials()));
            l.sortOrder = order++;
            validatePrice(c, l, pr, approvalTokens, offline, flags, authorizedBy);
            out.add(l);
        }
        return out;
    }

    private void validatePrice(Ctx c, SaleLine l, Presentation pr, List<String> tokens, boolean offline,
                               Set<String> flags, List<String> authorizedBy) {
        boolean belowList = l.unitPrice.compareTo(l.listPrice) < 0;
        boolean hasDiscount = l.discountPct.signum() > 0;
        if (!belowList && !hasDiscount) return;
        BigDecimal effective = l.unitPrice.multiply(BigDecimal.valueOf(100).subtract(l.discountPct))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal impliedPct = l.listPrice.signum() == 0 ? BigDecimal.ZERO
                : BigDecimal.ONE.subtract(effective.divide(l.listPrice, 6, RoundingMode.HALF_UP)).multiply(BigDecimal.valueOf(100));
        try {
            note(authorizedBy, approvals.require(Permission.POS_DESCUENTO, tokens, "Un descuento"));
            if (impliedPct.compareTo(c.settings().maxDiscountPct) > 0) {
                note(authorizedBy, approvals.require(Permission.POS_DESCUENTO_ESPECIAL, tokens,
                        "Un descuento mayor al " + c.settings().maxDiscountPct.stripTrailingZeros().toPlainString() + "%"));
            }
            if (pr.minPrice.signum() > 0 && effective.compareTo(pr.minPrice) < 0) {
                String who = approvals.require(Permission.POS_PRECIO_ESPECIAL, tokens,
                        "Vender " + l.productName + " por debajo del precio mínimo (" + Money.fmt(pr.minPrice) + ")");
                note(authorizedBy, who);
                l.priceAuthorizedBy = who == null ? c.me().fullName() : who;
            }
        } catch (ApiException e) {
            if (!offline) throw e;
            flags.add("DESCUENTO_SIN_APROBAR");
        }
    }

    private static void note(List<String> list, String who) {
        if (who != null && !list.contains(who)) list.add(who);
    }

    /** Recalcula promociones y totales de las líneas y la venta. */
    private SaleCalculator.Result compute(Ctx c, Sale s, List<SaleLine> ls) {
        List<SaleCalculator.LineInput> inputs = new ArrayList<>();
        for (SaleLine l : ls) {
            Product p = products.findById(l.productId).orElseThrow();
            l.promoId = null;
            l.promoName = null;
            BigDecimal promoDiscount = BigDecimal.ZERO;
            // Las promociones no se combinan con un precio especial digitado a mano.
            if (!l.noPromo && l.unitPrice.compareTo(l.listPrice) == 0) {
                var applied = PromotionEngine.best(c.promos(), p.id, categoryChain(c, p.categoryId), l.factor, l.unitPrice,
                        l.quantity, c.today(), c.scale());
                if (applied != null) {
                    l.promoId = applied.promo().id;
                    l.promoName = applied.promo().name;
                    promoDiscount = applied.discount();
                }
            }
            inputs.add(new SaleCalculator.LineInput(l.unitPrice, l.quantity, l.factor, promoDiscount, l.discountPct, l.taxRate, l.fixedTax));
        }
        SaleCalculator.Result r;
        try {
            r = SaleCalculator.calculate(inputs, c.settings().pricesIncludeTax, s.saleDiscountPct, s.saleDiscountAmount, c.scale());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        BigDecimal cost = BigDecimal.ZERO;
        for (int i = 0; i < ls.size(); i++) {
            SaleLine l = ls.get(i);
            var lr = r.lines().get(i);
            l.gross = lr.gross();
            l.discount = lr.discount();
            l.tax = lr.tax();
            l.lineTotal = lr.lineTotal();
            cost = cost.add(l.unitCost.multiply(l.quantity));
        }
        s.subtotal = r.subtotal();
        s.discountTotal = r.discountTotal();
        s.taxTotal = r.taxTotal();
        s.total = r.total();
        s.costTotal = cost.setScale(2, RoundingMode.HALF_UP);
        return r;
    }

    private List<Long> categoryChain(Ctx c, Long categoryId) {
        List<Long> chain = new ArrayList<>();
        Long cur = categoryId;
        while (cur != null && chain.size() < 5) {
            chain.add(cur);
            Category cat = c.categories().get(cur);
            cur = cat == null ? null : cat.parentId;
        }
        return chain;
    }

    /** Valida el descuento global de la venta contra los permisos. */
    private void validateSaleDiscount(Ctx c, SaleCalculator.Result r, List<String> tokens, boolean offline, Set<String> flags,
                                      List<String> authorizedBy) {
        if (r.saleDiscount().signum() == 0) return;
        try {
            note(authorizedBy, approvals.require(Permission.POS_DESCUENTO, tokens, "Un descuento"));
            BigDecimal base = r.subtotal().subtract(r.discountTotal().subtract(r.saleDiscount()));
            BigDecimal pct = SaleCalculator.effectivePct(r.saleDiscount(), base);
            if (pct.compareTo(c.settings().maxDiscountPct) > 0) {
                note(authorizedBy, approvals.require(Permission.POS_DESCUENTO_ESPECIAL, tokens,
                        "Un descuento mayor al " + c.settings().maxDiscountPct.stripTrailingZeros().toPlainString() + "%"));
            }
        } catch (ApiException e) {
            if (!offline) throw e;
            flags.add("DESCUENTO_SIN_APROBAR");
        }
    }

    // =====================================================================
    // Cobro directo (y venta sin internet)
    // =====================================================================

    @Transactional
    public SaleDto checkout(CheckoutRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        Ctx c = ctx();
        if (r.clientRef() != null && !r.clientRef().isBlank()) {
            Optional<Sale> dup = sales.findByTenantIdAndClientRef(c.tenantId(), r.clientRef());
            if (dup.isPresent()) return toDto(dup.get());
        }
        Set<String> flags = new LinkedHashSet<>();
        List<String> authorizedBy = new ArrayList<>();
        Sale s = new Sale();
        s.tenantId = c.tenantId();
        s.status = PAGADA;
        s.userId = c.me().userId();
        s.userName = c.me().fullName();
        s.clientRef = blank(r.clientRef());
        s.offline = r.offline();
        s.invoiceRequested = Boolean.TRUE.equals(r.electronicInvoice());
        s.notes = blank(r.notes());
        s.saleDiscountPct = nzNull(r.saleDiscountPct());
        s.saleDiscountAmount = nzNull(r.saleDiscountAmount());
        attachShift(c, s, r.offline(), r.offlineShiftId(), flags);
        applyCustomer(c, s, r.customer());
        List<SaleLine> ls = buildLines(c, r.lines(), r.approvals(), r.offline(), flags, authorizedBy);
        var result = compute(c, s, ls);
        validateSaleDiscount(c, result, r.approvals(), r.offline(), flags, authorizedBy);
        if (r.offline() && r.offlineCreatedAt() != null && r.offlineCreatedAt().isBefore(Instant.now())) {
            s.createdAt = r.offlineCreatedAt();
        }
        return finish(c, s, ls, r.payments(), r.approvals(), flags, authorizedBy, r.offline());
    }

    public record OnlineLine(Long presentationId, BigDecimal quantity, BigDecimal unitPrice) {
    }

    /**
     * Cobra un pedido de la tienda en línea al entregarlo. Se respetan los precios que vio el cliente (sin
     * promociones ni aprobaciones) y el domicilio entra como una línea del producto "Domicilio".
     */
    @Transactional
    public SaleDto checkoutOnline(long orderNumber, List<OnlineLine> items, BigDecimal deliveryFee, Long customerId,
                                  List<PaymentRequest> pays, String clientRef) {
        CurrentUser.require(Permission.POS_VENDER);
        Ctx c = ctx();
        Optional<Sale> dup = sales.findByTenantIdAndClientRef(c.tenantId(), clientRef);
        if (dup.isPresent()) return toDto(dup.get());
        Set<String> flags = new LinkedHashSet<>();
        Sale s = new Sale();
        s.tenantId = c.tenantId();
        s.status = PAGADA;
        s.userId = c.me().userId();
        s.userName = c.me().fullName();
        s.clientRef = clientRef;
        s.notes = "Pedido en línea #" + orderNumber;
        attachShift(c, s, false, null, flags);
        if (customerId != null) applyCustomer(c, s, new CustomerRequest(customerId, null, null, null, null, null));
        List<SaleLine> ls = buildLines(c, items.stream().map(i -> new LineRequest(i.presentationId(), i.quantity(), null, null)).toList(),
                null, false, flags, new ArrayList<>());
        for (int i = 0; i < ls.size(); i++) {
            SaleLine l = ls.get(i);
            l.listPrice = items.get(i).unitPrice().setScale(2, RoundingMode.HALF_UP);
            l.unitPrice = l.listPrice;
            l.noPromo = true;
        }
        if (deliveryFee != null && deliveryFee.signum() > 0) {
            Presentation pr = deliveryPresentation(c.tenantId());
            SaleLine l = new SaleLine();
            l.tenantId = c.tenantId();
            l.productId = pr.productId;
            l.presentationId = pr.id;
            l.productName = "Domicilio";
            l.presentationName = pr.name;
            l.factor = BigDecimal.ONE;
            l.quantity = BigDecimal.ONE.setScale(3);
            l.listPrice = deliveryFee.setScale(2, RoundingMode.HALF_UP);
            l.unitPrice = l.listPrice;
            l.taxName = "IVA";
            l.addedBy = c.me().fullName();
            l.sortOrder = ls.size();
            l.noPromo = true;
            ls.add(l);
        }
        compute(c, s, ls);
        return finish(c, s, ls, pays, null, flags, new ArrayList<>(), false);
    }

    /** Una línea de cotización o prefactura: producto del catálogo o línea libre (presentationId nulo). */
    public record QuoteLineInput(Long presentationId, String description, BigDecimal quantity, BigDecimal unitPrice,
                                 BigDecimal discountPct, String taxName, BigDecimal taxRate) {
    }

    /**
     * Cobra una cotización aceptada o una prefactura aprobada. Se respetan los precios y descuentos pactados (ya los
     * autorizó quien hizo el documento) y no se aplican promociones. El IVA de los productos del catálogo es el vigente
     * hoy (la prefactura avisa si cambió); las líneas libres llevan el del documento y salen como "Varios".
     */
    @Transactional
    public SaleDto checkoutQuote(String label, List<QuoteLineInput> items, BigDecimal saleDiscountAmount, Long customerId,
                                 List<PaymentRequest> pays, String clientRef, boolean electronicInvoice) {
        CurrentUser.require(Permission.POS_VENDER);
        Ctx c = ctx();
        Optional<Sale> dup = sales.findByTenantIdAndClientRef(c.tenantId(), clientRef);
        if (dup.isPresent()) return toDto(dup.get());
        Set<String> flags = new LinkedHashSet<>();
        Sale s = new Sale();
        s.tenantId = c.tenantId();
        s.status = PAGADA;
        s.userId = c.me().userId();
        s.userName = c.me().fullName();
        s.clientRef = clientRef;
        s.notes = label;
        s.invoiceRequested = electronicInvoice;
        s.saleDiscountAmount = nzNull(saleDiscountAmount);
        attachShift(c, s, false, null, flags);
        if (customerId != null) applyCustomer(c, s, new CustomerRequest(customerId, null, null, null, null, null));
        Long misc = null;
        List<LineRequest> reqs = new ArrayList<>();
        for (QuoteLineInput i : items) {
            Long pid = i.presentationId();
            if (pid == null) {
                if (misc == null) misc = servicePresentation(c.tenantId(), "VARIOS", "Varios").id;
                pid = misc;
            }
            reqs.add(new LineRequest(pid, i.quantity(), null, null));
        }
        List<SaleLine> ls = buildLines(c, reqs, null, false, flags, new ArrayList<>());
        for (int i = 0; i < ls.size(); i++) {
            SaleLine l = ls.get(i);
            QuoteLineInput in = items.get(i);
            if (products.findById(l.productId).map(p -> p.trackSerials).orElse(false)) {
                throw ApiException.badRequest(l.productName + " lleva serial o IMEI: cóbralo desde la caja para elegir cuál se entrega");
            }
            l.listPrice = in.unitPrice().setScale(2, RoundingMode.HALF_UP);
            l.unitPrice = l.listPrice;
            l.discountPct = in.discountPct() == null ? BigDecimal.ZERO : in.discountPct();
            l.noPromo = true;
            if (in.presentationId() == null) {
                l.productName = in.description();
                l.taxName = in.taxName() == null ? "IVA" : in.taxName();
                l.taxRate = in.taxRate() == null ? BigDecimal.ZERO : in.taxRate();
                l.fixedTax = BigDecimal.ZERO;
            }
        }
        compute(c, s, ls);
        return finish(c, s, ls, pays, null, flags, new ArrayList<>(), false);
    }

    /** Servicio "Domicilio" (sin inventario) que se crea la primera vez que se cobra un domicilio. */
    private Presentation deliveryPresentation(Long t) {
        return servicePresentation(t, "DOMICILIO", "Domicilio");
    }

    /** Producto de servicio sin inventario ni tienda en línea (domicilio, líneas libres de una cotización). */
    private Presentation servicePresentation(Long t, String sku, String name) {
        Product p = products.findBySku(t, sku).orElseGet(() -> {
            Product n = new Product();
            n.tenantId = t;
            n.sku = sku;
            n.name = name;
            n.unit = "UNIDAD";
            n.trackStock = false;
            n.onlineVisible = false;
            n.taxRate = BigDecimal.ZERO;
            return products.save(n);
        });
        return presentations.findByProductIdOrderBySortOrderAscIdAsc(p.id).stream().filter(x -> x.isBase).findFirst().orElseGet(() -> {
            Presentation x = new Presentation();
            x.tenantId = t;
            x.productId = p.id;
            x.name = "Servicio";
            x.price = BigDecimal.ZERO;
            x.isBase = true;
            return presentations.save(x);
        });
    }

    private void attachShift(Ctx c, Sale s, boolean offline, Long offlineShiftId, Set<String> flags) {
        Shift shift = null;
        if (offline && offlineShiftId != null) {
            shift = shifts.findByIdAndTenantId(offlineShiftId, c.tenantId()).orElse(null);
            if (shift != null && !shift.userId.equals(c.me().userId())) shift = null;
            if (shift != null && !"OPEN".equals(shift.status)) flags.add("TURNO_CERRADO");
        }
        if (shift == null) shift = shiftService.openShiftOfCurrentUser().orElse(null);
        if (shift == null && c.settings().requireOpenShift && !offline) {
            throw ApiException.conflict("Abre tu turno de caja antes de cobrar.");
        }
        if (shift == null && offline) flags.add("SIN_TURNO");
        if (shift != null) {
            s.shiftId = shift.id;
            s.registerId = shift.registerId;
        }
    }

    /** Pagos, número, stock y guardado. Común al cobro directo y al de cuentas abiertas. */
    private SaleDto finish(Ctx c, Sale s, List<SaleLine> ls, List<PaymentRequest> pays, List<String> tokens, Set<String> flags,
                           List<String> authorizedBy, boolean offline) {
        String[] creditWho = new String[1];
        List<Payment> ps = buildPayments(c, s, pays, tokens, offline, flags, creditWho);
        s.number = counters.next(c.tenantId(), "VENTA");
        s.completedAt = offline && s.createdAt.isBefore(Instant.now()) ? s.createdAt : Instant.now();
        s.status = PAGADA;
        if (!authorizedBy.isEmpty()) s.discountAuthorizedBy = String.join(", ", authorizedBy);
        s = sales.save(s);
        for (SaleLine l : ls) {
            l.saleId = s.id;
            lines.save(l);
        }
        for (Payment p : ps) {
            p.saleId = s.id;
            // Una venta hecha sin internet conserva la hora real del cobro (no la de sincronización).
            if (offline) p.createdAt = s.completedAt;
            payments.save(p);
        }
        flags.addAll(paymentService.afterSale(s, ps));
        for (Payment p : ps) {
            if ("FIADO".equals(p.method) && s.customerId != null) creditService.charge(s, p, creditWho[0]);
        }
        // Stock: se descuenta al cobrar (también en cuentas abiertas). Sin internet nunca se rechaza:
        // si faltaba stock queda negativo y la venta se marca para revisión.
        for (SaleLine l : ls) {
            Product p = products.lockById(l.productId, c.tenantId()).orElseThrow();
            BigDecimal qty = l.quantity.multiply(l.factor);
            if ("COMBO".equals(p.productType)) {
                // El combo descuenta cada componente y deja constancia de lo que salió (demanda, devoluciones, anulación).
                for (var part : advanced.parts(p)) {
                    Product cp = products.lockById(part.product().id, c.tenantId()).orElseThrow();
                    // Receta: lo que se pierde al preparar (merma) también sale del inventario.
                    BigDecimal per = part.quantity().multiply(l.factor)
                            .multiply(BigDecimal.ONE.add(part.wastePct().movePointLeft(2)));
                    SaleLineComponent slc = new SaleLineComponent();
                    slc.tenantId = c.tenantId();
                    slc.saleLineId = l.id;
                    slc.productId = cp.id;
                    slc.productName = cp.name;
                    slc.quantity = per.setScale(3, RoundingMode.HALF_UP);
                    slc.unitCost = cp.cost;
                    lineComponents.save(slc);
                    if (!cp.trackStock) continue;
                    BigDecimal need = per.multiply(l.quantity);
                    if (offline && cp.stock.compareTo(need) < 0 && !c.settings().allowNegativeStock) flags.add("STOCK_NEGATIVO");
                    inventory.applyLocked(cp, new InventoryService.Change(cp.id, need.negate(), InventoryService.VENTA, "SALE", s.id,
                            "Venta #" + s.number + " (combo " + p.name + ")", null, offline));
                }
                continue;
            }
            if (p.trackSerials) {
                serialService.sell(p, s.id, l.id, qty, com.tiendaposs.serials.SerialService.split(l.serials), s.customerId, s.customerName,
                        s.completedAt, LocalDate.ofInstant(s.completedAt, c.zone()), offline, flags);
            }
            if (!p.trackStock) continue;
            if (offline && p.stock.compareTo(qty) < 0 && !c.settings().allowNegativeStock) flags.add("STOCK_NEGATIVO");
            inventory.applyLocked(p, new InventoryService.Change(p.id, qty.negate(), InventoryService.VENTA, "SALE", s.id,
                    "Venta #" + s.number, null, offline));
        }
        if (!flags.isEmpty()) s.reviewFlags = String.join(",", flags);
        if (!authorizedBy.isEmpty()) {
            audit.sensitive("VENTA_AUTORIZADA", "SALE", s.id, "Venta #" + s.number + " por " + Money.fmt(s.total)
                    + " con descuento/precio autorizado por " + s.discountAuthorizedBy);
        }
        if (s.offline) {
            audit.log("VENTA_OFFLINE", "SALE", s.id, "Sincronizó la venta #" + s.number + " hecha sin internet"
                    + (flags.isEmpty() ? "" : " (para revisar: " + String.join(", ", flags) + ")"));
        }
        einvoice.onSaleCompleted(s);
        accounting.onSaleCompleted(s);
        return toDto(s);
    }

    private List<Payment> buildPayments(Ctx c, Sale s, List<PaymentRequest> reqs, List<String> tokens, boolean offline,
                                        Set<String> flags, String[] creditWho) {
        List<String> enabled = c.settings().paymentMethodList();
        BigDecimal remaining = s.total;
        BigDecimal applied = BigDecimal.ZERO, change = BigDecimal.ZERO;
        List<Payment> out = new ArrayList<>();
        for (PaymentRequest r : reqs) {
            String m = r.method().trim().toUpperCase();
            boolean advance = "ANTICIPO".equals(m);
            if (advance && !ADVANCE_ALLOWED.get()) throw ApiException.badRequest("El anticipo solo se aplica al entregar una orden de servicio");
            if (!advance && !enabled.contains(m) && !"OTRO".equals(m)) throw ApiException.badRequest("El medio de pago " + m + " no está habilitado");
            BigDecimal amount = r.amount().setScale(2, RoundingMode.HALF_UP);
            Payment p = new Payment();
            p.tenantId = c.tenantId();
            p.shiftId = s.shiftId;
            p.method = m;
            p.reference = blank(r.reference());
            p.userId = c.me().userId();
            p.userName = c.me().fullName();
            p.status = advance ? "CONFIRMADO" : paymentService.initialStatus(m, c.settings());
            if (remaining.signum() <= 0) throw ApiException.badRequest("Sobra un pago: la venta ya está cubierta");
            if (amount.compareTo(remaining) > 0) {
                if (!"EFECTIVO".equals(m)) {
                    throw ApiException.badRequest("Un pago por " + m.toLowerCase() + " no puede ser mayor que lo que falta (" + Money.fmt(remaining) + ")");
                }
                p.received = amount;
                p.amount = remaining;
                p.changeGiven = amount.subtract(remaining);
                change = change.add(p.changeGiven);
            } else {
                p.amount = amount;
                if ("EFECTIVO".equals(m)) p.received = amount;
            }
            if ("FIADO".equals(m)) {
                // Fiado: cliente registrado, cupo y mora; sin internet se acepta y queda para revisión.
                try {
                    creditWho[0] = creditService.validateCharge(s.customerId, p.amount, tokens);
                } catch (ApiException e) {
                    if (!offline) throw e;
                    flags.add(s.customerId == null ? "FIADO_SIN_CLIENTE" : "FIADO_SIN_AUTORIZAR");
                }
            }
            remaining = remaining.subtract(p.amount);
            applied = applied.add(p.amount);
            out.add(p);
        }
        if (remaining.signum() > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Falta " + Money.fmt(remaining) + " por pagar", Map.of("faltante", remaining));
        }
        s.paidTotal = applied;
        s.changeAmount = change;
        return out;
    }

    private void applyCustomer(Ctx c, Sale s, CustomerRequest r) {
        s.customerId = null;
        s.customerName = null;
        s.customerDoc = null;
        if (r == null) return;
        if (r.customerId() != null) {
            Customer cu = customers.findByIdAndTenantId(r.customerId(), c.tenantId())
                    .orElseThrow(() -> ApiException.badRequest("El cliente no existe"));
            s.customerId = cu.id;
            s.customerName = cu.name;
            s.customerDoc = cu.docNumber;
            return;
        }
        String name = blank(r.name());
        String doc = blank(r.docNumber());
        if (name == null && doc == null) return;
        Customer cu = doc == null ? null : customers.findByTenantIdAndDocNumber(c.tenantId(), doc).orElse(null);
        if (cu == null && (doc != null || blank(r.phone()) != null)) {
            cu = new Customer();
            cu.tenantId = c.tenantId();
            cu.name = name == null ? "Cliente " + doc : name;
            cu.docType = blank(r.docType());
            cu.docNumber = doc;
            cu.phone = blank(r.phone());
            cu.email = blank(r.email());
            cu = customers.save(cu);
        }
        s.customerId = cu == null ? null : cu.id;
        s.customerName = cu == null ? name : cu.name;
        s.customerDoc = cu == null ? doc : cu.docNumber;
    }

    // =====================================================================
    // Cuentas abiertas
    // =====================================================================

    @Transactional
    public SaleDto openAccount(AccountRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        Ctx c = ctx();
        // Serializa la apertura de cuentas de la tienda: evita que dos aparatos abran la cuenta 21 a la vez.
        com.tiendaposs.settings.ModulesService.check(c.settings(), "CUENTAS_ABIERTAS");
        counters.next(c.tenantId(), "CUENTAS_LOCK");
        long open = sales.countByTenantIdAndStatus(c.tenantId(), ABIERTA);
        if (open >= c.settings().openAccountsLimit) {
            throw ApiException.conflict("Ya hay " + open + " cuentas abiertas, el máximo de la tienda. Cobra o cierra alguna.");
        }
        String label = r.label().trim();
        for (Sale other : sales.findByTenantIdAndStatusInOrderByCreatedAtAsc(c.tenantId(), List.of(ABIERTA))) {
            if (label.equalsIgnoreCase(other.accountLabel)) throw ApiException.field("label", "ya hay una cuenta abierta con ese nombre");
        }
        Sale s = new Sale();
        s.tenantId = c.tenantId();
        s.status = ABIERTA;
        s.accountLabel = label;
        s.userId = c.me().userId();
        s.userName = c.me().fullName();
        s.notes = blank(r.notes());
        applyCustomer(c, s, r.customer());
        s = sales.save(s);
        audit.log("CUENTA_ABIERTA", "SALE", s.id, "Abrió la cuenta \"" + label + "\"");
        return toDto(s);
    }

    @Transactional(readOnly = true)
    public List<AccountSummaryDto> listAccounts() {
        Long t = TenantContext.require();
        return sales.findByTenantIdAndStatusInOrderByCreatedAtAsc(t, List.of(ABIERTA)).stream().map(s -> {
            List<SaleLine> ls = lines.findBySaleIdOrderBySortOrderAscIdAsc(s.id);
            int items = ls.stream().map(l -> l.quantity).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(0, RoundingMode.UP).intValue();
            return new AccountSummaryDto(s.id, s.accountLabel, s.userName, s.customerName, s.total, items, s.createdAt, s.version);
        }).toList();
    }

    @Transactional
    public SaleDto updateAccount(Long id, AccountRequest r) {
        Ctx c = ctx();
        Sale s = lockOpen(id, ABIERTA);
        String label = r.label().trim();
        for (Sale other : sales.findByTenantIdAndStatusInOrderByCreatedAtAsc(c.tenantId(), List.of(ABIERTA))) {
            if (!other.id.equals(s.id) && label.equalsIgnoreCase(other.accountLabel)) {
                throw ApiException.field("label", "ya hay una cuenta abierta con ese nombre");
            }
        }
        s.accountLabel = label;
        s.notes = blank(r.notes());
        applyCustomer(c, s, r.customer());
        return toDto(s);
    }

    /** Agrega a la cuenta. Si ya hay una línea igual (misma presentación, precio y descuento) suma la cantidad. */
    @Transactional
    public SaleDto addAccountLine(Long id, AddLineRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        Ctx c = ctx();
        Sale s = lockOpen(id, ABIERTA);
        List<SaleLine> current = lines.findBySaleIdOrderBySortOrderAscIdAsc(s.id);
        List<String> authorizedBy = new ArrayList<>();
        SaleLine nl = buildLines(c, List.of(r.line()), r.approvals(), false, new HashSet<>(), authorizedBy).get(0);
        SaleLine same = current.stream().filter(l -> Objects.equals(l.presentationId, nl.presentationId)
                && l.unitPrice.compareTo(nl.unitPrice) == 0 && l.discountPct.compareTo(nl.discountPct) == 0
                && l.modifiers == null && l.kitchenNote == null && l.sentQty.signum() == 0).findFirst().orElse(null);
        if (same != null) {
            same.quantity = same.quantity.add(nl.quantity);
            if (same.serials != null || nl.serials != null) {
                List<String> all = new ArrayList<>(com.tiendaposs.serials.SerialService.split(same.serials));
                all.addAll(com.tiendaposs.serials.SerialService.split(nl.serials));
                same.serials = com.tiendaposs.serials.SerialService.join(com.tiendaposs.serials.SerialService.normalize(all));
            }
        } else {
            nl.saleId = s.id;
            nl.sortOrder = current.size();
            current.add(nl);
        }
        recomputeAndSave(c, s, current);
        if (!authorizedBy.isEmpty()) {
            audit.sensitive("CUENTA_DESCUENTO", "SALE", s.id, "Descuento/precio en la cuenta \"" + s.accountLabel
                    + "\" autorizado por " + String.join(", ", authorizedBy));
        }
        return toDto(s);
    }

    @Transactional
    public SaleDto setAccountLineQty(Long id, Long lineId, BigDecimal qty) {
        Ctx c = ctx();
        Sale s = lockOpen(id, ABIERTA);
        List<SaleLine> current = lines.findBySaleIdOrderBySortOrderAscIdAsc(s.id);
        SaleLine l = current.stream().filter(x -> x.id.equals(lineId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Esa línea ya no está en la cuenta"));
        Product p = products.findById(l.productId).orElseThrow();
        if ("UNIDAD".equals(p.unit) && l.factor.stripTrailingZeros().scale() <= 0 && qty.stripTrailingZeros().scale() > 0) {
            throw ApiException.badRequest(p.name + " se vende por unidades enteras");
        }
        l.quantity = qty.setScale(3, RoundingMode.HALF_UP);
        recomputeAndSave(c, s, current);
        return toDto(s);
    }

    @Transactional
    public SaleDto removeAccountLine(Long id, Long lineId) {
        Ctx c = ctx();
        Sale s = lockOpen(id, ABIERTA);
        List<SaleLine> current = new ArrayList<>(lines.findBySaleIdOrderBySortOrderAscIdAsc(s.id));
        SaleLine l = current.stream().filter(x -> x.id.equals(lineId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Esa línea ya no está en la cuenta"));
        current.remove(l);
        // Si ya estaba en la cocina, la cocina debe enterarse de que se quitó.
        if (l.sentQty.signum() > 0) kitchen.onLineRemoved(s, l);
        lines.delete(l);
        audit.log("CUENTA_QUITO", "SALE", s.id, "Quitó " + plain(l.quantity) + " x " + l.productName + " de la cuenta \"" + s.accountLabel + "\"");
        recomputeAndSave(c, s, current);
        return toDto(s);
    }

    @Transactional
    public SaleDto checkoutAccount(Long id, AccountCheckoutRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        Ctx c = ctx();
        Sale s = lockOpen(id, ABIERTA);
        List<SaleLine> ls = lines.findBySaleIdOrderBySortOrderAscIdAsc(s.id);
        if (ls.isEmpty()) throw ApiException.badRequest("La cuenta está vacía");
        Set<String> flags = new LinkedHashSet<>();
        List<String> authorizedBy = new ArrayList<>();
        attachShift(c, s, false, null, flags);
        if (r.customer() != null) applyCustomer(c, s, r.customer());
        s.saleDiscountPct = nzNull(r.saleDiscountPct());
        s.saleDiscountAmount = nzNull(r.saleDiscountAmount());
        if (blank(r.notes()) != null) s.notes = r.notes().trim();
        for (SaleLine l : ls) {
            // Al cobrar se toma el costo vigente (lo que realmente sale del inventario).
            Product p = products.findById(l.productId).orElseThrow();
            l.unitCost = advanced.unitCost(p, l.factor);
        }
        var result = compute(c, s, ls);
        validateSaleDiscount(c, result, r.approvals(), false, flags, authorizedBy);
        String label = s.accountLabel;
        SaleDto dto = finish(c, s, ls, r.payments(), r.approvals(), flags, authorizedBy, false);
        audit.log("CUENTA_COBRADA", "SALE", s.id, "Cobró la cuenta \"" + label + "\": venta #" + dto.number() + " por " + Money.fmt(dto.total()));
        return dto;
    }

    /**
     * Anticipos de una orden de servicio (Fase 11): ya entraron el día en que se recibieron, así que al entregar se
     * descuentan como un pago interno "ANTICIPO" que no vuelve a contar como dinero recibido.
     */
    private static final ThreadLocal<Boolean> ADVANCE_ALLOWED = ThreadLocal.withInitial(() -> false);

    @Transactional
    public SaleDto checkoutAccountWithAdvance(Long id, AccountCheckoutRequest r) {
        ADVANCE_ALLOWED.set(true);
        try {
            return checkoutAccount(id, r);
        } finally {
            ADVANCE_ALLOWED.remove();
        }
    }

    /**
     * Cuenta abierta para cobrar un servicio (cita u orden de servicio). No pasa por el límite ni por el módulo de
     * cuentas abiertas: es el carrito de ese servicio, que se cobra en la caja.
     */
    @Transactional
    public Sale openServiceAccount(String label, Long customerId, Long staffId) {
        CurrentUser.require(Permission.POS_VENDER);
        Ctx c = ctx();
        Sale s = new Sale();
        s.tenantId = c.tenantId();
        s.status = ABIERTA;
        s.accountLabel = label.length() > 60 ? label.substring(0, 60) : label;
        s.userId = c.me().userId();
        s.userName = c.me().fullName();
        s.staffId = staffId;
        if (customerId != null) applyCustomer(c, s, new CustomerRequest(customerId, null, null, null, null, null));
        return sales.save(s);
    }

    /**
     * Agrega una línea a una cuenta de servicio sin juntarla con otras (cada repuesto o servicio por aparte), con el
     * precio ya pactado en la cita o la orden (price null = el de lista).
     */
    @Transactional
    public SaleDto addServiceLine(Long accountId, Long presentationId, BigDecimal quantity, BigDecimal price, String note, Long staffId) {
        Ctx c = ctx();
        Sale s = lockOpen(accountId, ABIERTA);
        List<SaleLine> current = new ArrayList<>(lines.findBySaleIdOrderBySortOrderAscIdAsc(s.id));
        SaleLine nl = buildLines(c, List.of(new LineRequest(presentationId, quantity, null, null)), List.of(), false, new HashSet<>(), new ArrayList<>()).get(0);
        if (price != null) nl.unitPrice = nl.listPrice = price.setScale(2, RoundingMode.HALF_UP);
        nl.kitchenNote = blank(note);
        nl.saleId = s.id;
        nl.staffId = staffId;
        nl.sortOrder = current.size();
        current.add(nl);
        recomputeAndSave(c, s, current);
        return toDto(s);
    }

    /** Cierra una cuenta sin cobrarla. Si tenía productos se considera una anulación: pide permiso y motivo. */
    @Transactional
    public void cancelAccount(Long id, String reason, List<String> tokens) {
        Sale s = lockOpen(id, ABIERTA);
        List<SaleLine> ls = lines.findBySaleIdOrderBySortOrderAscIdAsc(s.id);
        if (!ls.isEmpty()) {
            if (blank(reason) == null) throw ApiException.field("reason", "explica por qué se cierra sin cobrar");
            String who = approvals.require(Permission.POS_ANULAR, tokens, "Cerrar una cuenta con productos sin cobrarla");
            audit.sensitive("CUENTA_CANCELADA", "SALE", s.id, "Cerró sin cobrar la cuenta \"" + s.accountLabel + "\" por "
                    + Money.fmt(s.total) + ". Motivo: " + reason.trim() + (who == null ? "" : " (autorizó " + who + ")"));
        } else {
            audit.log("CUENTA_CANCELADA", "SALE", s.id, "Cerró la cuenta vacía \"" + s.accountLabel + "\"");
        }
        lines.deleteBySaleId(s.id);
        sales.delete(s);
    }

    // =====================================================================
    // Restaurante (Fase 10): platos con modificadores, dividir y unir cuentas
    // =====================================================================

    /**
     * Agrega un plato a la cuenta con sus modificadores (su precio ya viene sumado en el precio de la línea) y una nota
     * para la cocina. Siempre es una línea nueva: dos hamburguesas con distinto término son platos distintos.
     */
    @Transactional
    public SaleDto addDishLine(Long accountId, LineRequest line, List<String> approvals, String modifiers, String note) {
        CurrentUser.require(Permission.POS_VENDER);
        Ctx c = ctx();
        Sale s = lockOpen(accountId, ABIERTA);
        List<SaleLine> current = new ArrayList<>(lines.findBySaleIdOrderBySortOrderAscIdAsc(s.id));
        List<String> authorizedBy = new ArrayList<>();
        SaleLine nl = buildLines(c, List.of(line), approvals, false, new HashSet<>(), authorizedBy).get(0);
        nl.saleId = s.id;
        nl.modifiers = blank(modifiers);
        nl.kitchenNote = blank(note);
        nl.sortOrder = current.size();
        current.add(nl);
        recomputeAndSave(c, s, current);
        return toDto(s);
    }

    public record SplitLine(Long lineId, BigDecimal quantity) {
    }

    /**
     * Divide la cuenta: lo elegido pasa a una cuenta nueva en la misma mesa (para que cada quien pague lo suyo). Lo que
     * ya estaba en la cocina conserva ese estado en la cuenta nueva.
     */
    @Transactional
    public SaleDto splitAccount(Long accountId, List<SplitLine> parts) {
        CurrentUser.require(Permission.POS_VENDER);
        Ctx c = ctx();
        Sale from = lockOpen(accountId, ABIERTA);
        List<SaleLine> src = new ArrayList<>(lines.findBySaleIdOrderBySortOrderAscIdAsc(from.id));
        if (parts == null || parts.isEmpty()) throw ApiException.badRequest("Elige qué productos pasan a la otra cuenta");
        Sale to = new Sale();
        to.tenantId = from.tenantId;
        to.status = ABIERTA;
        to.tableId = from.tableId;
        to.guests = null;
        to.userId = from.userId;
        to.userName = from.userName;
        int n = 2;
        Set<String> used = new HashSet<>();
        for (Sale o : sales.findByTenantIdAndStatusInOrderByCreatedAtAsc(c.tenantId(), List.of(ABIERTA))) used.add(o.accountLabel.toLowerCase());
        String base = from.accountLabel.replaceAll(" · parte \\d+$", "");
        while (used.contains((base + " · parte " + n).toLowerCase())) n++;
        to.accountLabel = base + " · parte " + n;
        to = sales.save(to);
        List<SaleLine> dst = new ArrayList<>();
        for (SplitLine part : parts) {
            SaleLine l = src.stream().filter(x -> x.id.equals(part.lineId())).findFirst()
                    .orElseThrow(() -> ApiException.notFound("Esa línea ya no está en la cuenta"));
            BigDecimal q = part.quantity() == null ? l.quantity : part.quantity().setScale(3, RoundingMode.HALF_UP);
            if (q.signum() <= 0) continue;
            if (q.compareTo(l.quantity) > 0) throw ApiException.badRequest("De " + l.productName + " solo hay " + plain(l.quantity));
            if (q.compareTo(l.quantity) == 0) {
                src.remove(l);
                l.saleId = to.id;
                dst.add(l);
            } else {
                SaleLine copy = copyLine(l, to.id, q);
                BigDecimal sentMoved = l.sentQty.min(q);
                copy.sentQty = sentMoved;
                l.sentQty = l.sentQty.subtract(sentMoved);
                l.quantity = l.quantity.subtract(q);
                dst.add(copy);
            }
        }
        if (dst.isEmpty()) throw ApiException.badRequest("Elige al menos un producto con cantidad");
        if (src.isEmpty()) throw ApiException.badRequest("No puedes pasar todo: deja algo en la cuenta original");
        recomputeAndSave(c, from, src);
        recomputeAndSave(c, to, dst);
        audit.log("CUENTA_DIVIDIDA", "SALE", from.id, "Dividió la cuenta \"" + from.accountLabel + "\": " + dst.size()
                + " producto(s) pasaron a \"" + to.accountLabel + "\"");
        return toDto(to);
    }

    /** Une dos cuentas (dos mesas que se juntan): todo pasa a la cuenta destino y la otra se cierra. */
    @Transactional
    public SaleDto mergeAccounts(Long fromId, Long intoId) {
        CurrentUser.require(Permission.POS_VENDER);
        if (fromId.equals(intoId)) throw ApiException.badRequest("Elige otra cuenta");
        Ctx c = ctx();
        Sale from = lockOpen(fromId, ABIERTA);
        Sale into = lockOpen(intoId, ABIERTA);
        List<SaleLine> dst = new ArrayList<>(lines.findBySaleIdOrderBySortOrderAscIdAsc(into.id));
        for (SaleLine l : lines.findBySaleIdOrderBySortOrderAscIdAsc(from.id)) {
            l.saleId = into.id;
            dst.add(l);
        }
        if (into.guests != null && from.guests != null) into.guests = into.guests + from.guests;
        recomputeAndSave(c, into, dst);
        kitchen.onAccountMerged(from, into);
        audit.log("CUENTAS_UNIDAS", "SALE", into.id, "Unió la cuenta \"" + from.accountLabel + "\" a \"" + into.accountLabel + "\"");
        sales.delete(from);
        return toDto(into);
    }

    private SaleLine copyLine(SaleLine l, Long saleId, BigDecimal qty) {
        SaleLine n = new SaleLine();
        n.saleId = saleId;
        n.tenantId = l.tenantId;
        n.productId = l.productId;
        n.presentationId = l.presentationId;
        n.productName = l.productName;
        n.presentationName = l.presentationName;
        n.factor = l.factor;
        n.quantity = qty;
        n.listPrice = l.listPrice;
        n.unitPrice = l.unitPrice;
        n.priceAuthorizedBy = l.priceAuthorizedBy;
        n.discountPct = l.discountPct;
        n.taxName = l.taxName;
        n.taxRate = l.taxRate;
        n.fixedTax = l.fixedTax;
        n.unitCost = l.unitCost;
        n.addedBy = l.addedBy;
        n.modifiers = l.modifiers;
        n.kitchenNote = l.kitchenNote;
        return n;
    }

    private void recomputeAndSave(Ctx c, Sale s, List<SaleLine> ls) {
        for (int i = 0; i < ls.size(); i++) ls.get(i).sortOrder = i;
        if (ls.isEmpty()) {
            s.subtotal = s.discountTotal = s.taxTotal = s.total = s.costTotal = BigDecimal.ZERO;
        } else {
            compute(c, s, ls);
        }
        lines.saveAll(ls);
        sales.save(s);
    }

    private Sale lockOpen(Long id, String status) {
        Sale s = sales.lockById(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("La cuenta ya no existe"));
        if (!status.equals(s.status)) {
            throw ApiException.conflict(PAGADA.equals(s.status) ? "Esa cuenta ya fue cobrada" : "Esa cuenta ya no está abierta");
        }
        return s;
    }

    // =====================================================================
    // Ventas en espera
    // =====================================================================

    @Transactional
    public SaleDto hold(HoldRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        Ctx c = ctx();
        Sale s = new Sale();
        s.tenantId = c.tenantId();
        s.status = EN_ESPERA;
        s.accountLabel = blank(r.label()) == null ? "Venta en espera" : r.label().trim();
        s.userId = c.me().userId();
        s.userName = c.me().fullName();
        s.notes = blank(r.notes());
        applyCustomer(c, s, r.customer());
        // Se guarda tal como está (sin validar descuentos: eso pasa al cobrar).
        List<LineRequest> plain = r.lines().stream().map(l -> new LineRequest(l.presentationId(), l.quantity(), null, null, l.serials())).toList();
        List<SaleLine> ls = buildLines(c, plain, null, false, new HashSet<>(), new ArrayList<>());
        for (int i = 0; i < ls.size(); i++) {
            LineRequest orig = r.lines().get(i);
            if (orig.unitPrice() != null) ls.get(i).unitPrice = orig.unitPrice();
            if (orig.discountPct() != null) ls.get(i).discountPct = orig.discountPct();
        }
        compute(c, s, ls);
        s = sales.save(s);
        for (SaleLine l : ls) {
            l.saleId = s.id;
            lines.save(l);
        }
        audit.log("VENTA_EN_ESPERA", "SALE", s.id, "Guardó una venta en espera por " + Money.fmt(s.total));
        return toDto(s);
    }

    @Transactional(readOnly = true)
    public List<SaleDto> listHolds() {
        return sales.findByTenantIdAndStatusInOrderByCreatedAtAsc(TenantContext.require(), List.of(EN_ESPERA)).stream()
                .map(this::toDto).toList();
    }

    /** Devuelve la venta guardada para seguirla en la caja y la quita de la lista de espera. */
    @Transactional
    public SaleDto recoverHold(Long id) {
        Sale s = lockOpen(id, EN_ESPERA);
        SaleDto dto = toDto(s);
        lines.deleteBySaleId(s.id);
        sales.delete(s);
        audit.log("VENTA_RECUPERADA", "SALE", s.id, "Recuperó la venta en espera \"" + s.accountLabel + "\"");
        return dto;
    }

    // =====================================================================
    // Anulación, historial, detalle
    // =====================================================================

    @Transactional
    public SaleDto voidSale(Long id, VoidRequest r) {
        Long t = TenantContext.require();
        AuthUser me = CurrentUser.get();
        Sale s = sales.lockById(id, t).orElseThrow(() -> ApiException.notFound("Venta no encontrada"));
        if (!PAGADA.equals(s.status)) throw ApiException.badRequest("Solo se pueden anular ventas pagadas");
        if (s.refundedTotal.signum() > 0) throw ApiException.badRequest("Esta venta ya tiene devoluciones: no se puede anular completa");
        Shift shift = s.shiftId == null ? null : shifts.findById(s.shiftId).orElse(null);
        if (shift != null && !"OPEN".equals(shift.status)) {
            throw ApiException.badRequest("El turno de esta venta ya se cerró. Registra una devolución en su lugar.");
        }
        String who = approvals.require(Permission.POS_ANULAR, r.approvals(), "Anular una venta");
        s.status = ANULADA;
        s.voidedAt = Instant.now();
        s.voidedBy = me.fullName();
        s.voidReason = r.reason().trim();
        s.voidAuthorizedBy = who;
        for (Payment p : payments.findBySaleIdOrderByIdAsc(s.id)) p.status = "ANULADO";
        creditService.reverseSale(s, "Anulación de la venta #" + s.number);
        serialService.voidSale(s.id);
        for (SaleLine l : lines.findBySaleIdOrderBySortOrderAscIdAsc(s.id)) {
            for (SaleLineComponent slc : lineComponents.findBySaleLineIdOrderByIdAsc(l.id)) {
                Product cp = products.lockById(slc.productId, t).orElseThrow();
                if (!cp.trackStock) continue;
                inventory.applyLocked(cp, new InventoryService.Change(cp.id, slc.quantity.multiply(l.quantity), InventoryService.ANULACION,
                        "SALE", s.id, "Anulación de la venta #" + s.number + " (combo " + l.productName + ")", null, true));
            }
            Product p = products.lockById(l.productId, t).orElseThrow();
            if (!p.trackStock) continue;
            inventory.applyLocked(p, new InventoryService.Change(p.id, l.quantity.multiply(l.factor), InventoryService.ANULACION,
                    "SALE", s.id, "Anulación de la venta #" + s.number, null, true));
        }
        einvoice.onSaleVoided(s, s.voidReason);
        accounting.onSaleVoided(s);
        audit.sensitive("VENTA_ANULADA", "SALE", s.id, "Anuló la venta #" + s.number + " por " + Money.fmt(s.total)
                + ". Motivo: " + s.voidReason + (who == null ? "" : " (autorizó " + who + ")"));
        return toDto(s);
    }

    @Transactional(readOnly = true)
    public SaleDto get(Long id) {
        Sale s = sales.findByIdAndTenantId(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Venta no encontrada"));
        AuthUser me = CurrentUser.get();
        if (PAGADA.equals(s.status) || ANULADA.equals(s.status)) {
            if (!s.userId.equals(me.userId()) && !me.can(Permission.POS_HISTORIAL) && !me.can(Permission.POS_DEVOLUCION)
                    && !me.can(Permission.POS_ANULAR) && !me.can(Permission.REPORTES_VER)) {
                throw ApiException.forbidden("Solo puedes ver tus propias ventas");
            }
        }
        return toDto(s);
    }

    @Transactional(readOnly = true)
    public SaleDto byNumber(Long number) {
        Sale s = sales.findByTenantIdAndNumber(TenantContext.require(), number)
                .orElseThrow(() -> ApiException.notFound("No existe la venta #" + number));
        return get(s.id);
    }

    @Transactional(readOnly = true)
    public com.tiendaposs.catalog.CatalogDtos.PageDto<SaleSummaryDto> history(Long userId, Long shiftId, String status, Long number,
                                                                            BigDecimal minTotal, BigDecimal maxTotal, boolean onlyReview,
                                                                            Instant from, Instant to, int page, int size) {
        Long t = TenantContext.require();
        AuthUser me = CurrentUser.get();
        Long u = me.can(Permission.POS_HISTORIAL) || me.can(Permission.REPORTES_VER) ? userId : me.userId();
        var res = sales.history(t, u, shiftId, status, number, minTotal, maxTotal, onlyReview, from, to,
                org.springframework.data.domain.PageRequest.of(page, size));
        List<SaleSummaryDto> items = res.getContent().stream().map(s -> {
            List<String> methods = payments.findBySaleIdOrderByIdAsc(s.id).stream().map(p -> p.method).distinct().toList();
            int count = lines.findBySaleIdOrderBySortOrderAscIdAsc(s.id).stream().map(l -> l.quantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(0, RoundingMode.UP).intValue();
            return new SaleSummaryDto(s.id, s.number, s.status, s.userName, s.customerName, s.total, s.refundedTotal, methods,
                    count, s.offline, flags(s), s.reviewedAt != null, s.completedAt);
        }).toList();
        return new com.tiendaposs.catalog.CatalogDtos.PageDto<>(items, res.getTotalElements(), res.getNumber(), res.getTotalPages());
    }

    /** Gerencia marca como revisada una venta hecha sin internet con observaciones. */
    @Transactional
    public SaleDto markReviewed(Long id) {
        CurrentUser.require(Permission.POS_HISTORIAL);
        Sale s = sales.findByIdAndTenantId(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Venta no encontrada"));
        if (s.reviewFlags == null) throw ApiException.badRequest("Esta venta no tiene observaciones");
        s.reviewedAt = Instant.now();
        audit.log("VENTA_REVISADA", "SALE", s.id, "Revisó la venta #" + s.number + " (" + s.reviewFlags + ")");
        return toDto(s);
    }

    public SaleDto toDto(Sale s) {
        boolean costs = CurrentUser.get().can(Permission.COSTOS_VER);
        List<LineDto> ls = s.id == null ? List.of() : lines.findBySaleIdOrderBySortOrderAscIdAsc(s.id).stream()
                .map(l -> new LineDto(l.id, l.productId, l.presentationId, l.productName, l.presentationName, l.factor, l.quantity,
                        l.listPrice, l.unitPrice, l.priceAuthorizedBy, l.discountPct, l.promoName, l.gross, l.discount, l.taxName,
                        l.taxRate, l.tax, l.lineTotal, costs ? l.unitCost : null, l.returnedQty, l.addedBy,
                        l.serials == null ? null : com.tiendaposs.serials.SerialService.split(l.serials), componentsOf(l),
                        l.modifiers, l.kitchenNote, l.sentQty))
                .toList();
        List<PaymentDto> ps = s.id == null ? List.of() : payments.findBySaleIdOrderByIdAsc(s.id).stream()
                .map(p -> new PaymentDto(p.id, p.method, p.amount, p.received, p.changeGiven, p.reference, p.status, p.userName, p.createdAt))
                .toList();
        List<ReturnDto> rs = new ArrayList<>();
        if (s.id != null) {
            List<SaleReturn> list = returns.findBySaleIdOrderByIdAsc(s.id);
            if (!list.isEmpty()) {
                Map<Long, String> names = new HashMap<>();
                ls.forEach(l -> names.put(l.id(), l.productName()));
                Map<Long, List<SaleReturnLine>> byReturn = returnLines.findByReturnIdIn(list.stream().map(x -> x.id).toList())
                        .stream().collect(Collectors.groupingBy(x -> x.returnId));
                for (SaleReturn r : list) {
                    rs.add(new ReturnDto(r.id, r.number, r.reason, r.refundMethod, r.refundAmount, r.userName, r.authorizedBy, r.createdAt,
                            byReturn.getOrDefault(r.id, List.of()).stream().map(x -> new ReturnLineDto(x.saleLineId, names.get(x.saleLineId),
                                    x.quantity, x.amount, x.restock, x.condition)).toList()));
                }
            }
        }
        return new SaleDto(s.id, s.number, s.status, s.accountLabel, s.shiftId, s.registerId, s.userId, s.userName, s.customerId,
                s.customerName, s.customerDoc, s.subtotal, s.discountTotal, s.taxTotal, s.total, costs ? s.costTotal : null,
                s.paidTotal, s.changeAmount, s.refundedTotal, s.saleDiscountPct, s.saleDiscountAmount, s.discountAuthorizedBy,
                s.notes, s.clientRef, s.offline, flags(s), s.reviewedAt, s.createdAt, s.completedAt, s.voidedAt, s.voidedBy,
                s.voidReason, s.voidAuthorizedBy, ls, ps, rs, s.id == null ? null : einvoice.forSale(s.tenantId, s.id),
                s.tableId, s.guests, s.prebillAt, s.tipAmount, s.tipMethod);
    }

    private List<ComponentLineDto> componentsOf(SaleLine l) {
        if (l.id == null) return null;
        List<SaleLineComponent> list = lineComponents.findBySaleLineIdOrderByIdAsc(l.id);
        return list.isEmpty() ? null : list.stream().map(x -> new ComponentLineDto(x.productId, x.productName, x.quantity)).toList();
    }

    private static List<String> flags(Sale s) {
        return s.reviewFlags == null ? List.of() : List.of(s.reviewFlags.split(","));
    }

    private static BigDecimal nzNull(BigDecimal v) {
        return v == null || v.signum() == 0 ? null : v;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
