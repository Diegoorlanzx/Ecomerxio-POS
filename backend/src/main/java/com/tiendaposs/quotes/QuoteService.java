package com.tiendaposs.quotes;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogRepos.PresentationRepository;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Presentation;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Counters;
import com.tiendaposs.common.Money;
import com.tiendaposs.pos.Customer;
import com.tiendaposs.pos.PosRepos.CustomerRepository;
import com.tiendaposs.pos.PosRepos.SaleRepository;
import com.tiendaposs.pos.SaleCalculator;
import com.tiendaposs.pos.SaleDtos.PaymentRequest;
import com.tiendaposs.pos.SaleDtos.SaleDto;
import com.tiendaposs.pos.SaleService;
import com.tiendaposs.quotes.QuoteRepos.QuoteLineRepository;
import com.tiendaposs.quotes.QuoteRepos.QuoteRepository;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.settings.StoreSettings;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.tenant.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Cotizaciones y prefacturas.
 *
 * <p>Cotización: oferta comercial con vigencia (arts. 845 y ss. del Código de Comercio: obliga a la tienda mientras esté
 * vigente). BORRADOR → ENVIADA → ACEPTADA / RECHAZADA → CONVERTIDA (pasó a prefactura) o COBRADA; VENCIDA se calcula con
 * la fecha. Prefactura: los mismos datos revisados antes de facturar; BORRADOR → APROBADA (sin errores y con las
 * advertencias aceptadas) → FACTURADA (cobrada en la caja). Ninguna consume la numeración de la DIAN ni reemplaza la
 * factura: la venta y su documento electrónico se generan al cobrar.</p>
 */
@Service
public class QuoteService {

    public static final String COTIZACION = "COTIZACION";
    public static final String PREFACTURA = "PREFACTURA";
    public static final int DEFAULT_VALID_DAYS = 15;
    /** Igual que defaultTerms('PREFACTURA') en frontend/src/lib/quoteTypes.ts. */
    static final String PREFACTURA_TERMS = "Documento de revisión previo a la factura. No es factura de venta ni documento equivalente y no "
            + "sirve como soporte de costos o gastos. Por favor revisa tus datos y avísanos si algo no está bien.";

    private static final Set<String> QUOTE_EDITABLE = Set.of("BORRADOR", "ENVIADA");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final QuoteRepository quotes;
    private final QuoteLineRepository lines;
    private final ProductRepository products;
    private final PresentationRepository presentations;
    private final CustomerRepository customers;
    private final SaleRepository sales;
    private final SaleService saleService;
    private final SettingsService settings;
    private final TenantRepository tenants;
    private final Counters counters;
    private final AuditService audit;
    private final PrefacturaValidator validator;
    @org.springframework.beans.factory.annotation.Autowired
    private com.tiendaposs.notifications.NotificationService notifications;

    public QuoteService(QuoteRepository quotes, QuoteLineRepository lines, ProductRepository products,
                        PresentationRepository presentations, CustomerRepository customers, SaleRepository sales,
                        SaleService saleService, SettingsService settings, TenantRepository tenants, Counters counters,
                        AuditService audit, PrefacturaValidator validator) {
        this.quotes = quotes;
        this.lines = lines;
        this.products = products;
        this.presentations = presentations;
        this.customers = customers;
        this.sales = sales;
        this.saleService = saleService;
        this.settings = settings;
        this.tenants = tenants;
        this.counters = counters;
        this.audit = audit;
        this.validator = validator;
    }

    // ------------------------------------------------------------------ DTOs

    public record LineRequest(Long presentationId,
                              @Size(max = 200, message = "máximo 200 caracteres") String description,
                              @NotNull(message = "es obligatoria") @DecimalMin(value = "0.001", message = "debe ser mayor que cero") BigDecimal quantity,
                              @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal unitPrice,
                              @DecimalMin(value = "0", message = "no puede ser negativo") @DecimalMax(value = "100", message = "máximo 100") BigDecimal discountPct,
                              /** Solo en líneas libres: el IVA lo trae el producto en las demás. */
                              @DecimalMin(value = "0", message = "no puede ser negativo") @DecimalMax(value = "100", message = "máximo 100") BigDecimal taxRate) {
    }

    public record QuoteRequest(
            Long customerId,
            @Size(max = 160, message = "máximo 160 caracteres") String customerName,
            @Size(max = 10) String customerDocType,
            @Size(max = 30, message = "máximo 30 caracteres") String customerDoc,
            @Size(max = 1, message = "es un solo dígito") String customerDv,
            @Pattern(regexp = "^$|NATURAL|JURIDICA", message = "elige persona natural o jurídica") String customerPersonType,
            @Email(message = "no es un correo válido") @Size(max = 160) String customerEmail,
            @Size(max = 30, message = "máximo 30 caracteres") String customerPhone,
            @Size(max = 200, message = "máximo 200 caracteres") String customerAddress,
            @Pattern(regexp = "^$|[0-9]{5}", message = "son 5 dígitos (código DANE del municipio)") String customerMunicipality,
            LocalDate validUntil,
            @Size(max = 1000, message = "máximo 1000 caracteres") String notes,
            @Size(max = 2000, message = "máximo 2000 caracteres") String terms,
            @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal saleDiscountAmount,
            @NotNull @Size(min = 1, message = "agrega al menos un producto o servicio") List<@Valid LineRequest> lines) {
    }

    public record LineDto(Long id, Long productId, Long presentationId, String description, String presentationName,
                          BigDecimal factor, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPct, String taxName,
                          BigDecimal taxRate, BigDecimal fixedTax, BigDecimal gross, BigDecimal discount, BigDecimal tax,
                          BigDecimal lineTotal, boolean free) {
        static LineDto of(QuoteLine l) {
            return new LineDto(l.id, l.productId, l.presentationId, l.description, l.presentationName, l.factor, l.quantity,
                    l.unitPrice, l.discountPct, l.taxName, l.taxRate, l.fixedTax, l.gross, l.discount, l.tax, l.lineTotal,
                    l.presentationId == null);
        }
    }

    public record QuoteDto(Long id, String kind, long number, String code, String status, Long customerId, String customerName,
                           String customerDocType, String customerDoc, String customerDv, String customerPersonType,
                           String customerEmail, String customerPhone, String customerAddress, String customerMunicipality,
                           LocalDate issueDate, LocalDate validUntil, String notes, String terms, BigDecimal saleDiscountAmount,
                           BigDecimal subtotal, BigDecimal discountTotal, BigDecimal taxTotal, BigDecimal total,
                           boolean pricesIncludeTax, Long sourceId, String sourceCode, Long prefacturaId, String prefacturaCode,
                           Long saleId, Long saleNumber, String publicToken, String createdBy, Instant createdAt,
                           Instant updatedAt, Instant sentAt, Instant decidedAt, String decidedBy, String decisionNote,
                           String clientCheck, String clientCheckNote, Instant clientCheckAt, String approvedBy,
                           Instant approvedAt, List<String> approvedWarnings, Instant cancelledAt, String cancelReason,
                           List<LineDto> lines, PrefacturaValidator.Result validation) {
    }

    public record SummaryDto(Long id, String kind, String code, String status, String customerName, String customerDoc,
                             BigDecimal total, LocalDate issueDate, LocalDate validUntil, String createdBy, Instant createdAt,
                             String clientCheck) {
    }

    // ------------------------------------------------------------------ Consultas

    public List<SummaryDto> list(String kind, String status, String q) {
        CurrentUser.require(Permission.COTIZACIONES_VER);
        Long t = TenantContext.require();
        String k = PREFACTURA.equalsIgnoreCase(kind) ? PREFACTURA : COTIZACION;
        String term = q == null ? "" : q.trim().toLowerCase();
        String exact = term.replaceAll("^(cot|pf)-0*", "");
        LocalDate today = today(t);
        return quotes.search(t, k, term.isEmpty() ? "" : "%" + term + "%", exact, PageRequest.of(0, 200)).stream()
                .filter(x -> status == null || status.isBlank() || status.equals(effectiveStatus(x, today)))
                .map(x -> new SummaryDto(x.id, x.kind, x.code(), effectiveStatus(x, today), x.customerName, x.customerDoc, x.total,
                        x.issueDate, x.validUntil, x.createdBy, x.createdAt, x.clientCheck))
                .toList();
    }

    public QuoteDto get(Long id) {
        CurrentUser.require(Permission.COTIZACIONES_VER);
        return toDto(find(id));
    }

    // ------------------------------------------------------------------ Crear y editar

    @Transactional
    public QuoteDto create(String kind, QuoteRequest r) {
        CurrentUser.require(Permission.COTIZACIONES_CREAR);
        Long t = TenantContext.require();
        Quote q = new Quote();
        q.tenantId = t;
        q.kind = PREFACTURA.equalsIgnoreCase(kind) ? PREFACTURA : COTIZACION;
        q.status = "BORRADOR";
        q.issueDate = today(t);
        q.publicToken = token();
        q.createdBy = CurrentUser.get().fullName();
        q.number = counters.next(t, q.kind);
        fill(q, r);
        q = quotes.save(q);
        saveLines(q, r.lines());
        audit.log(q.isQuote() ? "COTIZACION_CREADA" : "PREFACTURA_CREADA", "QUOTE", q.id,
                "Creó la " + label(q) + " " + q.code() + " para " + nvl(q.customerName, "cliente sin nombre") + " por " + Money.fmt(q.total));
        return toDto(q);
    }

    @Transactional
    public QuoteDto update(Long id, QuoteRequest r) {
        CurrentUser.require(Permission.COTIZACIONES_CREAR);
        Quote q = find(id);
        if (q.isQuote() ? !QUOTE_EDITABLE.contains(q.status) : !Set.of("BORRADOR", "APROBADA").contains(q.status)) {
            throw ApiException.conflict("La " + label(q) + " está " + statusLabel(q.status) + ": ya no se puede editar");
        }
        fill(q, r);
        if (!q.isQuote()) {
            // Cualquier cambio obliga a revisar y aprobar de nuevo; el reporte de error del cliente queda atendido.
            q.status = "BORRADOR";
            q.approvedAt = null;
            q.approvedBy = null;
            q.approvedWarnings = null;
            if ("CON_ERROR".equals(q.clientCheck)) {
                q.clientCheck = null;
                q.clientCheckNote = null;
                q.clientCheckAt = null;
            }
        }
        lines.deleteByQuoteId(q.id);
        lines.flush();
        saveLines(q, r.lines());
        audit.log(q.isQuote() ? "COTIZACION_EDITADA" : "PREFACTURA_EDITADA", "QUOTE", q.id, "Editó la " + label(q) + " " + q.code());
        return toDto(q);
    }

    private void fill(Quote q, QuoteRequest r) {
        Long t = q.tenantId;
        Customer cu = null;
        if (r.customerId() != null) {
            cu = customers.findByIdAndTenantId(r.customerId(), t).orElseThrow(() -> ApiException.field("customerId", "el cliente no existe"));
        }
        q.customerId = cu == null ? null : cu.id;
        // Lo que viene en el formulario manda; lo que falta se toma de la ficha del cliente.
        q.customerName = pick(r.customerName(), cu == null ? null : cu.name);
        q.customerDocType = pick(r.customerDocType(), cu == null ? null : cu.docType);
        q.customerDoc = pick(digitsIfNumeric(r.customerDocType(), r.customerDoc()), cu == null ? null : cu.docNumber);
        q.customerDv = "NIT".equals(q.customerDocType) ? pick(r.customerDv(), cu == null ? null : cu.dv) : null;
        q.customerPersonType = pick(r.customerPersonType(), cu == null ? null : cu.personType);
        q.customerEmail = pick(r.customerEmail(), cu == null ? null : nvl(cu.invoiceEmail, cu.email));
        q.customerPhone = pick(r.customerPhone(), cu == null ? null : cu.phone);
        q.customerAddress = pick(r.customerAddress(), cu == null ? null : cu.address);
        q.customerMunicipality = pick(r.customerMunicipality(), cu == null ? null : cu.municipalityCode);
        if (q.isQuote() && q.customerName == null) throw ApiException.field("customerName", "escribe a quién va dirigida la cotización");
        LocalDate today = today(t);
        if (q.isQuote()) {
            LocalDate until = r.validUntil() == null ? today.plusDays(DEFAULT_VALID_DAYS) : r.validUntil();
            if (until.isBefore(today)) throw ApiException.field("validUntil", "no puede ser una fecha pasada");
            if (until.isAfter(today.plusDays(365))) throw ApiException.field("validUntil", "máximo un año de vigencia");
            q.validUntil = until;
        } else {
            q.validUntil = null;
        }
        q.notes = blank(r.notes());
        q.terms = blank(r.terms());
        q.saleDiscountAmount = r.saleDiscountAmount() == null ? BigDecimal.ZERO : r.saleDiscountAmount();
    }

    /** Arma las líneas desde el catálogo (o libres), valida descuentos con los permisos de quien cotiza y recalcula. */
    private void saveLines(Quote q, List<LineRequest> reqs) {
        StoreSettings st = settings.forTenant(q.tenantId);
        Set<String> perms = CurrentUser.get().permissions();
        List<QuoteLine> out = new ArrayList<>();
        int order = 0;
        for (LineRequest r : reqs) {
            QuoteLine l = new QuoteLine();
            l.tenantId = q.tenantId;
            l.quoteId = q.id;
            l.quantity = r.quantity().setScale(3, RoundingMode.HALF_UP);
            l.discountPct = r.discountPct() == null ? BigDecimal.ZERO : r.discountPct();
            l.sortOrder = order++;
            if (r.presentationId() != null) {
                Presentation pr = presentations.findByIdAndTenantId(r.presentationId(), q.tenantId)
                        .orElseThrow(() -> ApiException.badRequest("Un producto del documento ya no existe"));
                Product p = products.findByIdAndTenantId(pr.productId, q.tenantId).orElseThrow();
                if (!p.sellable()) throw ApiException.badRequest("Elige la variante de " + p.name + " (talla, color…)");
                if (!pr.active || !"ACTIVE".equals(p.status)) throw ApiException.badRequest(p.name + " (" + pr.name + ") ya no se vende");
                l.productId = p.id;
                l.presentationId = pr.id;
                l.description = blank(r.description()) != null ? r.description().trim()
                        : p.name + (p.variantLabel == null ? "" : " " + p.variantLabel);
                l.presentationName = pr.name;
                l.factor = pr.factor;
                l.unitPrice = (r.unitPrice() == null ? pr.price : r.unitPrice()).setScale(2, RoundingMode.HALF_UP);
                l.taxName = p.taxName;
                l.taxRate = p.taxRate == null ? BigDecimal.ZERO : p.taxRate;
                l.fixedTax = p.fixedTax == null ? BigDecimal.ZERO : p.fixedTax;
                checkPrice(st, perms, l, pr);
            } else {
                if (blank(r.description()) == null) throw ApiException.badRequest("Describe la línea libre (por ejemplo: \"Instalación\")");
                if (r.unitPrice() == null) throw ApiException.badRequest("Ponle precio a \"" + r.description().trim() + "\"");
                l.description = r.description().trim();
                l.unitPrice = r.unitPrice().setScale(2, RoundingMode.HALF_UP);
                l.taxName = st.defaultTaxName == null ? "IVA" : st.defaultTaxName;
                l.taxRate = r.taxRate() == null ? BigDecimal.ZERO : r.taxRate();
                if (l.discountPct.signum() > 0 && !perms.contains(Permission.POS_DESCUENTO.name())) {
                    throw ApiException.forbidden("No tienes permiso para dar descuentos");
                }
            }
            out.add(l);
        }
        compute(q, out, st);
        lines.saveAll(out);
        quotes.save(q);
    }

    /** Las mismas reglas de la caja: descuento, descuento especial y precio mínimo según los permisos de quien cotiza. */
    private static void checkPrice(StoreSettings st, Set<String> perms, QuoteLine l, Presentation pr) {
        boolean below = l.unitPrice.compareTo(pr.price) < 0;
        if (!below && l.discountPct.signum() == 0) return;
        BigDecimal effective = l.unitPrice.multiply(BigDecimal.valueOf(100).subtract(l.discountPct)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal implied = pr.price.signum() == 0 ? BigDecimal.ZERO
                : BigDecimal.ONE.subtract(effective.divide(pr.price, 6, RoundingMode.HALF_UP)).multiply(BigDecimal.valueOf(100));
        if (!perms.contains(Permission.POS_DESCUENTO.name())) {
            throw ApiException.forbidden("No tienes permiso para dar descuentos ni bajar precios (" + l.description + ")");
        }
        if (implied.compareTo(st.maxDiscountPct) > 0 && !perms.contains(Permission.POS_DESCUENTO_ESPECIAL.name())) {
            throw ApiException.forbidden("El descuento de " + l.description + " supera el máximo de "
                    + st.maxDiscountPct.stripTrailingZeros().toPlainString() + "%: debe cotizarlo alguien con permiso de descuento especial");
        }
        if (pr.minPrice != null && pr.minPrice.signum() > 0 && effective.compareTo(pr.minPrice) < 0
                && !perms.contains(Permission.POS_PRECIO_ESPECIAL.name())) {
            throw ApiException.forbidden(l.description + " queda por debajo del precio mínimo (" + Money.fmt(pr.minPrice) + ")");
        }
    }

    /** Calcula con el mismo motor de la caja (incluido o no el IVA en el precio, según los ajustes de la tienda). */
    SaleCalculator.Result compute(Quote q, List<QuoteLine> ls, StoreSettings st) {
        int scale = Money.scale(tenants.findById(q.tenantId).map(Tenant::getCurrency).orElse("COP"));
        List<SaleCalculator.LineInput> in = ls.stream().map(l -> new SaleCalculator.LineInput(l.unitPrice, l.quantity, l.factor,
                BigDecimal.ZERO, l.discountPct, l.taxRate, l.fixedTax)).toList();
        SaleCalculator.Result r;
        try {
            r = SaleCalculator.calculate(in, st.pricesIncludeTax, null, q.saleDiscountAmount, scale);
        } catch (IllegalArgumentException e) {
            throw ApiException.field("saleDiscountAmount", e.getMessage().toLowerCase());
        }
        for (int i = 0; i < ls.size(); i++) {
            var lr = r.lines().get(i);
            QuoteLine l = ls.get(i);
            l.gross = lr.gross();
            l.discount = lr.discount();
            l.tax = lr.tax();
            l.lineTotal = lr.lineTotal();
        }
        q.subtotal = r.subtotal();
        q.discountTotal = r.discountTotal();
        q.taxTotal = r.taxTotal();
        q.total = r.total();
        q.pricesIncludeTax = st.pricesIncludeTax;
        return r;
    }

    // ------------------------------------------------------------------ Flujo de la cotización

    /** Marca como enviada (la tienda la compartió por WhatsApp, correo o impresa). */
    @Transactional
    public QuoteDto markSent(Long id) {
        CurrentUser.require(Permission.COTIZACIONES_CREAR);
        Quote q = find(id);
        if (q.isQuote()) {
            if (!QUOTE_EDITABLE.contains(q.status)) throw ApiException.conflict("La cotización está " + statusLabel(q.status));
            q.status = "ENVIADA";
        } else if ("ANULADA".equals(q.status) || "FACTURADA".equals(q.status)) {
            throw ApiException.conflict("La prefactura está " + statusLabel(q.status));
        }
        if (q.sentAt == null) q.sentAt = Instant.now();
        audit.log(q.isQuote() ? "COTIZACION_ENVIADA" : "PREFACTURA_ENVIADA", "QUOTE", q.id, "Envió la " + label(q) + " " + q.code() + " al cliente");
        return toDto(quotes.save(q));
    }

    public record DecisionRequest(boolean accepted, @Size(max = 160) String by, @Size(max = 500) String note) {
    }

    /** La tienda registra la respuesta que el cliente le dio por otro medio. */
    @Transactional
    public QuoteDto decide(Long id, DecisionRequest r) {
        CurrentUser.require(Permission.COTIZACIONES_CREAR);
        Quote q = find(id);
        applyDecision(q, r.accepted(), blank(r.by()) == null ? q.customerName + " (registrado por " + CurrentUser.get().fullName() + ")"
                : r.by().trim(), r.note(), null);
        return toDto(q);
    }

    private void applyDecision(Quote q, boolean accepted, String by, String note, String ip) {
        if (!q.isQuote()) throw ApiException.badRequest("Solo las cotizaciones se aceptan o rechazan");
        if (!QUOTE_EDITABLE.contains(q.status)) throw ApiException.conflict("La cotización ya está " + statusLabel(q.status));
        if (accepted && "VENCIDA".equals(effectiveStatus(q, today(q.tenantId)))) {
            throw ApiException.conflict("La cotización venció el " + q.validUntil + ". Pide una nueva con precios actualizados.");
        }
        q.status = accepted ? "ACEPTADA" : "RECHAZADA";
        q.decidedAt = Instant.now();
        q.decidedBy = by;
        q.decisionNote = blank(note);
        q.decisionIp = ip;
        if (q.sentAt == null) q.sentAt = q.decidedAt;
        quotes.save(q);
        audit.logInCurrentTx(q.tenantId, null, by, accepted ? "COTIZACION_ACEPTADA" : "COTIZACION_RECHAZADA",
                (accepted ? "Aceptó" : "Rechazó") + " la cotización " + q.code() + (q.decisionNote == null ? "" : ": " + q.decisionNote));
    }

    /** Pasa una cotización aceptada (o enviada) a prefactura para revisar los datos antes de facturar. */
    @Transactional
    public QuoteDto toPrefactura(Long id) {
        CurrentUser.require(Permission.COTIZACIONES_CREAR);
        Quote q = find(id);
        if (!q.isQuote()) throw ApiException.badRequest("Ya es una prefactura");
        if (!Set.of("ACEPTADA", "ENVIADA", "BORRADOR").contains(q.status)) {
            throw ApiException.conflict("La cotización está " + statusLabel(q.status));
        }
        Quote pf = copy(q, PREFACTURA);
        pf.sourceId = q.id;
        pf.terms = PREFACTURA_TERMS;
        pf = quotes.save(pf);
        copyLines(q, pf);
        q.status = "CONVERTIDA";
        quotes.save(q);
        audit.log("PREFACTURA_CREADA", "QUOTE", pf.id, "Creó la prefactura " + pf.code() + " desde la cotización " + q.code());
        return toDto(pf);
    }

    /** Copia como cotización nueva (para re-cotizar una vencida o rechazada); los precios se conservan. */
    @Transactional
    public QuoteDto duplicate(Long id) {
        CurrentUser.require(Permission.COTIZACIONES_CREAR);
        Quote q = find(id);
        Quote c = copy(q, q.kind);
        c.validUntil = c.isQuote() ? today(q.tenantId).plusDays(DEFAULT_VALID_DAYS) : null;
        c = quotes.save(c);
        copyLines(q, c);
        audit.log("COTIZACION_DUPLICADA", "QUOTE", c.id, "Copió " + q.code() + " como " + c.code());
        return toDto(c);
    }

    private Quote copy(Quote q, String kind) {
        Quote c = new Quote();
        c.tenantId = q.tenantId;
        c.kind = kind;
        c.status = "BORRADOR";
        c.number = counters.next(q.tenantId, kind);
        c.issueDate = today(q.tenantId);
        c.validUntil = PREFACTURA.equals(kind) ? null : q.validUntil;
        c.publicToken = token();
        c.createdBy = CurrentUser.get().fullName();
        c.customerId = q.customerId;
        c.customerName = q.customerName;
        c.customerDocType = q.customerDocType;
        c.customerDoc = q.customerDoc;
        c.customerDv = q.customerDv;
        c.customerPersonType = q.customerPersonType;
        c.customerEmail = q.customerEmail;
        c.customerPhone = q.customerPhone;
        c.customerAddress = q.customerAddress;
        c.customerMunicipality = q.customerMunicipality;
        c.notes = q.notes;
        c.terms = q.terms;
        c.saleDiscountAmount = q.saleDiscountAmount;
        c.subtotal = q.subtotal;
        c.discountTotal = q.discountTotal;
        c.taxTotal = q.taxTotal;
        c.total = q.total;
        c.pricesIncludeTax = q.pricesIncludeTax;
        return c;
    }

    private void copyLines(Quote from, Quote to) {
        List<QuoteLine> out = new ArrayList<>();
        for (QuoteLine l : lines.findByQuoteIdOrderBySortOrderAscIdAsc(from.id)) {
            QuoteLine n = new QuoteLine();
            n.tenantId = l.tenantId;
            n.quoteId = to.id;
            n.productId = l.productId;
            n.presentationId = l.presentationId;
            n.description = l.description;
            n.presentationName = l.presentationName;
            n.factor = l.factor;
            n.quantity = l.quantity;
            n.unitPrice = l.unitPrice;
            n.discountPct = l.discountPct;
            n.taxName = l.taxName;
            n.taxRate = l.taxRate;
            n.fixedTax = l.fixedTax;
            n.gross = l.gross;
            n.discount = l.discount;
            n.tax = l.tax;
            n.lineTotal = l.lineTotal;
            n.sortOrder = l.sortOrder;
            out.add(n);
        }
        lines.saveAll(out);
    }

    // ------------------------------------------------------------------ Prefactura: validar y aprobar

    public PrefacturaValidator.Result validate(Long id) {
        CurrentUser.require(Permission.COTIZACIONES_VER);
        Quote q = find(id);
        return validator.validate(q, lines.findByQuoteIdOrderBySortOrderAscIdAsc(q.id));
    }

    public record ApproveRequest(boolean acceptWarnings) {
    }

    /**
     * Da por buenos los datos: sin errores, y con las advertencias aceptadas expresamente (quedan guardadas con quién y
     * cuándo). Los datos del cliente pasan a su ficha (o se crea) para que la factura salga con ellos.
     */
    @Transactional
    public QuoteDto approve(Long id, ApproveRequest r) {
        CurrentUser.require(Permission.PREFACTURAS_APROBAR);
        Quote q = find(id);
        if (q.isQuote()) throw ApiException.badRequest("Las cotizaciones no se aprueban: pásala a prefactura");
        if (!"BORRADOR".equals(q.status)) throw ApiException.conflict("La prefactura está " + statusLabel(q.status));
        List<QuoteLine> ls = lines.findByQuoteIdOrderBySortOrderAscIdAsc(q.id);
        PrefacturaValidator.Result v = validator.validate(q, ls);
        if (v.errors() > 0) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Corrige " + v.errors() + (v.errors() == 1 ? " error" : " errores") + " antes de aprobar", Map.of("validation", v));
        }
        List<String> warnings = v.issues().stream().filter(i -> "WARNING".equals(i.severity())).map(PrefacturaValidator.Issue::message).toList();
        if (!warnings.isEmpty() && !r.acceptWarnings()) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Revisa y acepta las advertencias para aprobar", Map.of("validation", v));
        }
        q.customerId = upsertCustomer(q).id;
        q.status = "APROBADA";
        q.approvedBy = CurrentUser.get().fullName();
        q.approvedAt = Instant.now();
        String joined = String.join("\n", warnings);
        q.approvedWarnings = warnings.isEmpty() ? null : joined.length() > 2000 ? joined.substring(0, 2000) : joined;
        quotes.save(q);
        audit.sensitive("PREFACTURA_APROBADA", "QUOTE", q.id, "Aprobó la prefactura " + q.code() + " por " + Money.fmt(q.total)
                + (warnings.isEmpty() ? "" : " aceptando " + warnings.size() + " advertencia(s)"));
        return toDto(q);
    }

    /** La ficha del cliente queda con los datos revisados (los de la prefactura mandan). */
    private Customer upsertCustomer(Quote q) {
        Customer c = q.customerId == null ? null : customers.findByIdAndTenantId(q.customerId, q.tenantId).orElse(null);
        if (c == null && q.customerDoc != null) c = customers.findByTenantIdAndDocNumber(q.tenantId, q.customerDoc).orElse(null);
        if (c == null) {
            c = new Customer();
            c.tenantId = q.tenantId;
        }
        c.name = q.customerName;
        c.docType = q.customerDocType;
        c.docNumber = q.customerDoc;
        c.dv = q.customerDv;
        c.personType = q.customerPersonType;
        c.invoiceEmail = q.customerEmail;
        if (c.email == null) c.email = q.customerEmail;
        if (q.customerPhone != null) c.phone = q.customerPhone;
        if (q.customerAddress != null) c.address = q.customerAddress;
        if (q.customerMunicipality != null) c.municipalityCode = q.customerMunicipality;
        return customers.save(c);
    }

    // ------------------------------------------------------------------ Cobrar

    public record ChargeRequest(@NotNull @Size(min = 1, message = "registra al menos un pago") List<@Valid PaymentRequest> payments,
                                Boolean electronicInvoice) {
    }

    /** Cobra en la caja (turno abierto): crea la venta, descuenta el inventario y, si se pide, la factura electrónica. */
    @Transactional
    public SaleDto charge(Long id, ChargeRequest r) {
        CurrentUser.require(Permission.COTIZACIONES_VER);
        Quote q = find(id);
        if (q.isQuote() && !"ACEPTADA".equals(q.status)) {
            throw ApiException.conflict("Solo se cobra una cotización aceptada (está " + statusLabel(effectiveStatus(q, today(q.tenantId))) + ")");
        }
        if (!q.isQuote() && !"APROBADA".equals(q.status)) {
            throw ApiException.conflict("Aprueba la prefactura antes de cobrarla (está " + statusLabel(q.status) + ")");
        }
        boolean invoice = Boolean.TRUE.equals(r.electronicInvoice());
        if (invoice && q.customerId == null) {
            throw ApiException.badRequest("Para la factura a nombre del cliente pasa la cotización a prefactura y apruébala");
        }
        List<SaleService.QuoteLineInput> items = lines.findByQuoteIdOrderBySortOrderAscIdAsc(q.id).stream()
                .map(l -> new SaleService.QuoteLineInput(l.presentationId, l.description, l.quantity, l.unitPrice, l.discountPct,
                        l.taxName, l.taxRate)).toList();
        SaleDto sale = saleService.checkoutQuote((q.isQuote() ? "Cotización " : "Prefactura ") + q.code(), items,
                q.saleDiscountAmount, q.customerId, r.payments(), "QUOTE-" + q.id, invoice);
        q.saleId = sale.id();
        q.status = q.isQuote() ? "COBRADA" : "FACTURADA";
        quotes.save(q);
        audit.log(q.isQuote() ? "COTIZACION_COBRADA" : "PREFACTURA_FACTURADA", "QUOTE", q.id,
                "Cobró la " + label(q) + " " + q.code() + ": venta #" + sale.number());
        return sale;
    }

    // ------------------------------------------------------------------ Anular

    public record CancelRequest(@NotBlank(message = "explica por qué se anula") @Size(max = 300) String reason) {
    }

    @Transactional
    public QuoteDto cancel(Long id, CancelRequest r) {
        CurrentUser.require(Permission.COTIZACIONES_CREAR);
        Quote q = find(id);
        if (Set.of("ANULADA", "COBRADA", "FACTURADA", "CONVERTIDA").contains(q.status)) {
            throw ApiException.conflict("La " + label(q) + " ya está " + statusLabel(q.status));
        }
        q.status = "ANULADA";
        q.cancelledAt = Instant.now();
        q.cancelReason = r.reason().trim();
        audit.log(q.isQuote() ? "COTIZACION_ANULADA" : "PREFACTURA_ANULADA", "QUOTE", q.id,
                "Anuló la " + label(q) + " " + q.code() + ". Motivo: " + q.cancelReason);
        return toDto(quotes.save(q));
    }

    // ------------------------------------------------------------------ Enlace público (el cliente, sin cuenta)

    public record PublicLine(String description, String presentationName, BigDecimal quantity, BigDecimal unitPrice,
                             BigDecimal discountPct, BigDecimal taxRate, BigDecimal lineTotal) {
    }

    public record PublicQuote(String kind, String code, String status, String storeName, String storeLegalName, String storeTaxId,
                              String storePhone, String storeWhatsapp, String storeEmail, String storeAddress, String storeCity,
                              String storeLogo, String currency, String customerName, String customerDocType, String customerDoc,
                              String customerDv, String customerPersonType, String customerEmail, String customerPhone,
                              String customerAddress, String customerMunicipality, LocalDate issueDate, LocalDate validUntil,
                              String notes, String terms, boolean pricesIncludeTax, BigDecimal subtotal, BigDecimal discountTotal,
                              BigDecimal taxTotal, BigDecimal total, List<PublicLine> lines, String decidedBy, Instant decidedAt,
                              String clientCheck, Instant clientCheckAt) {
    }

    public PublicQuote publicView(String token) {
        Quote q = byToken(token);
        Tenant t = tenants.findById(q.tenantId).orElseThrow();
        String logo = t.getLogo() != null && t.getLogo().length() < 300_000 ? t.getLogo() : null;
        boolean pf = !q.isQuote();
        return new PublicQuote(q.kind, q.code(), effectiveStatus(q, today(q.tenantId)), t.getName(), t.getLegalName(), t.getTaxId(),
                t.getPhone(), t.getWhatsapp(), t.getEmail(), t.getAddress(), t.getCity(), logo, t.getCurrency(), q.customerName,
                q.customerDocType, q.customerDoc, pf ? q.customerDv : null, pf ? q.customerPersonType : null, q.customerEmail,
                pf ? q.customerPhone : null, pf ? q.customerAddress : null, pf ? q.customerMunicipality : null, q.issueDate, q.validUntil,
                q.notes, q.terms, q.pricesIncludeTax, q.subtotal, q.discountTotal, q.taxTotal, q.total,
                lines.findByQuoteIdOrderBySortOrderAscIdAsc(q.id).stream().map(l -> new PublicLine(l.description,
                        l.presentationId == null || "Unidad".equals(l.presentationName) ? null : l.presentationName, l.quantity,
                        l.unitPrice, l.discountPct, l.taxRate, l.lineTotal)).toList(),
                q.decidedBy, q.decidedAt, q.clientCheck, q.clientCheckAt);
    }

    public record PublicDecision(boolean accepted, @NotBlank(message = "escribe tu nombre") @Size(max = 160) String name,
                                 @Size(max = 500) String note) {
    }

    @Transactional
    public PublicQuote publicDecide(String token, PublicDecision r) {
        Quote q = byToken(token);
        TenantContext.set(q.tenantId);
        try {
            applyDecision(q, r.accepted(), r.name().trim() + " (enlace)", r.note(), AuditService.clientIp());
        } finally {
            TenantContext.clear();
        }
        tellStore(q, (r.accepted() ? "Aceptaron la cotización " : "Rechazaron la cotización ") + q.code(),
                r.name().trim() + " · " + Money.fmt(q.total) + (blank(r.note()) == null ? "" : " · \"" + r.note().trim() + "\""));
        return publicView(token);
    }

    public record PublicCheck(boolean correct, @Size(max = 500) String note) {
    }

    /** En la prefactura el cliente confirma sus datos o avisa qué está mal (eso bloquea la aprobación hasta corregir). */
    @Transactional
    public PublicQuote publicCheck(String token, PublicCheck r) {
        Quote q = byToken(token);
        if (q.isQuote()) throw ApiException.badRequest("Esto es una cotización");
        if (!Set.of("BORRADOR", "APROBADA").contains(q.status)) throw ApiException.conflict("La prefactura ya está " + statusLabel(q.status));
        if (!r.correct() && blank(r.note()) == null) throw ApiException.field("note", "cuéntanos qué dato está mal");
        q.clientCheck = r.correct() ? "CONFIRMADO" : "CON_ERROR";
        q.clientCheckNote = blank(r.note());
        q.clientCheckAt = Instant.now();
        if (!r.correct() && "APROBADA".equals(q.status)) {
            // Ya estaba aprobada: vuelve a revisión para que nadie la facture con el dato malo.
            q.status = "BORRADOR";
            q.approvedAt = null;
            q.approvedBy = null;
        }
        quotes.save(q);
        tellStore(q, (r.correct() ? "El cliente confirmó sus datos en " : "El cliente reportó un error en ") + q.code(),
                nvl(q.customerName, "") + (q.clientCheckNote == null ? "" : " · \"" + q.clientCheckNote + "\""));
        audit.logInCurrentTx(q.tenantId, null, q.customerName, r.correct() ? "PREFACTURA_CONFIRMADA" : "PREFACTURA_REPORTADA",
                (r.correct() ? "El cliente confirmó sus datos en la prefactura " : "El cliente reportó un error en la prefactura ") + q.code()
                        + (q.clientCheckNote == null ? "" : ": " + q.clientCheckNote));
        return publicView(token);
    }

    // ------------------------------------------------------------------ utilidades

    /** Aviso a la tienda (campana y canales configurados) cuando el cliente responde desde el enlace. */
    private void tellStore(Quote q, String title, String body) {
        notifications.notifyNow(q.tenantId, com.tiendaposs.notifications.NotificationType.COTIZACION_RESPUESTA, title, body,
                "/cotizaciones/" + q.id, "QUOTE:" + q.id + ":" + Instant.now().toEpochMilli());
    }

    private Quote find(Long id) {
        return quotes.findByIdAndTenantId(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Documento no encontrado"));
    }

    private Quote byToken(String token) {
        Quote q = token == null || token.length() < 20 ? null : quotes.findByPublicToken(token).orElse(null);
        if (q == null || "ANULADA".equals(q.status)) throw ApiException.notFound("Este enlace no existe o el documento fue anulado");
        return q;
    }

    /** VENCIDA no se guarda: una cotización sin respuesta vence sola al pasar la fecha. */
    static String effectiveStatus(Quote q, LocalDate today) {
        if (q.isQuote() && QUOTE_EDITABLE.contains(q.status) && q.validUntil != null && q.validUntil.isBefore(today)) return "VENCIDA";
        return q.status;
    }

    QuoteDto toDto(Quote q) {
        List<QuoteLine> ls = lines.findByQuoteIdOrderBySortOrderAscIdAsc(q.id);
        Quote src = q.sourceId == null ? null : quotes.findById(q.sourceId).orElse(null);
        Quote pf = q.isQuote() ? quotes.findBySourceIdAndKind(q.id, PREFACTURA).stream()
                .filter(x -> !"ANULADA".equals(x.status)).findFirst().orElse(null) : null;
        Long saleNumber = q.saleId == null ? null : sales.findById(q.saleId).map(s -> s.number).orElse(null);
        PrefacturaValidator.Result v = !q.isQuote() && Set.of("BORRADOR", "APROBADA").contains(q.status) ? validator.validate(q, ls) : null;
        return new QuoteDto(q.id, q.kind, q.number, q.code(), effectiveStatus(q, today(q.tenantId)), q.customerId, q.customerName,
                q.customerDocType, q.customerDoc, q.customerDv, q.customerPersonType, q.customerEmail, q.customerPhone,
                q.customerAddress, q.customerMunicipality, q.issueDate, q.validUntil, q.notes, q.terms, q.saleDiscountAmount,
                q.subtotal, q.discountTotal, q.taxTotal, q.total, q.pricesIncludeTax, q.sourceId, src == null ? null : src.code(),
                pf == null ? null : pf.id, pf == null ? null : pf.code(), q.saleId, saleNumber, q.publicToken, q.createdBy,
                q.createdAt, q.updatedAt, q.sentAt, q.decidedAt, q.decidedBy, q.decisionNote, q.clientCheck, q.clientCheckNote,
                q.clientCheckAt, q.approvedBy, q.approvedAt,
                q.approvedWarnings == null ? List.of() : List.of(q.approvedWarnings.split("\n")), q.cancelledAt, q.cancelReason,
                ls.stream().map(LineDto::of).toList(), v);
    }

    LocalDate today(Long tenantId) {
        return LocalDate.now(ZoneId.of(tenants.findById(tenantId).map(Tenant::getTimezone).orElse("America/Bogota")));
    }

    static long daysLeft(Quote q, LocalDate today) {
        return q.validUntil == null ? 0 : ChronoUnit.DAYS.between(today, q.validUntil);
    }

    private static String token() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String label(Quote q) {
        return q.isQuote() ? "cotización" : "prefactura";
    }

    static String statusLabel(String s) {
        return switch (s) {
            case "BORRADOR" -> "en borrador";
            case "ENVIADA" -> "enviada";
            case "ACEPTADA" -> "aceptada";
            case "RECHAZADA" -> "rechazada";
            case "VENCIDA" -> "vencida";
            case "CONVERTIDA" -> "convertida en prefactura";
            case "COBRADA" -> "cobrada";
            case "APROBADA" -> "aprobada";
            case "FACTURADA" -> "facturada";
            case "ANULADA" -> "anulada";
            default -> s.toLowerCase();
        };
    }

    private static String digitsIfNumeric(String docType, String doc) {
        if (doc == null) return null;
        return Set.of("CC", "NIT", "TI", "RC").contains(docType) ? doc.replaceAll("[^0-9]", "") : doc.trim();
    }

    private static String pick(String a, String b) {
        return blank(a) != null ? a.trim() : blank(b);
    }

    private static String nvl(String a, String b) {
        return a != null ? a : b;
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
