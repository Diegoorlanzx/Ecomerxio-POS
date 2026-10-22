package com.tiendaposs.einvoice;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Counters;
import com.tiendaposs.einvoice.EInvoiceProvider.*;
import com.tiendaposs.einvoice.EInvoiceRepos.DocumentRepository;
import com.tiendaposs.einvoice.EInvoiceRepos.EventRepository;
import com.tiendaposs.fiscal.FiscalCodes;
import com.tiendaposs.fiscal.FiscalProfile;
import com.tiendaposs.fiscal.FiscalRepos.FiscalProfileRepository;
import com.tiendaposs.fiscal.FiscalRepos.ResolutionRepository;
import com.tiendaposs.fiscal.FiscalService;
import com.tiendaposs.fiscal.NumberingResolution;
import com.tiendaposs.pos.Customer;
import com.tiendaposs.pos.PosRepos.*;
import com.tiendaposs.pos.Sale;
import com.tiendaposs.pos.SaleLine;
import com.tiendaposs.pos.SaleReturn;
import com.tiendaposs.pos.SaleReturnLine;
import com.tiendaposs.purchasing.GoodsReceipt;
import com.tiendaposs.purchasing.GoodsReceiptLine;
import com.tiendaposs.purchasing.PurchasingRepos.GoodsReceiptLineRepository;
import com.tiendaposs.purchasing.PurchasingRepos.GoodsReceiptRepository;
import com.tiendaposs.purchasing.PurchasingRepos.SupplierRepository;
import com.tiendaposs.purchasing.Supplier;
import com.tiendaposs.purchasing.SupplierService;
import com.tiendaposs.security.CurrentUser;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fase 13B: emisión de documentos electrónicos.
 * <ul>
 *   <li>El documento se crea en la MISMA transacción de la venta, devolución o anulación (si la venta falla, no existe ni
 *       gasta número). Se envía después, desde una cola en la base de datos (outbox).</li>
 *   <li>Regla de oro: si no se sabe qué pasó con un envío (DESCONOCIDO), primero se consulta al proveedor por la
 *       referencia externa; solo si nunca lo recibió se reenvía. Nunca hay dos documentos por la misma operación.</li>
 *   <li>Antes de enviar se valida lo que se puede validar aquí (datos del emisor, del comprador, productos sin
 *       clasificación tributaria): si falta algo queda BLOQUEADO con la explicación y no se gasta un envío.</li>
 * </ul>
 */
@Service
public class EInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(EInvoiceService.class);

    public static final String PENDIENTE = "PENDIENTE", EN_PROCESO = "EN_PROCESO", DESCONOCIDO = "DESCONOCIDO",
            ACEPTADO = "ACEPTADO", RECHAZADO = "RECHAZADO", BLOQUEADO = "BLOQUEADO", ANULADO = "ANULADO";
    public static final String POS = "POS_ELECTRONICO", FACTURA = "FACTURA", NOTA_CREDITO = "NOTA_CREDITO",
            SOPORTE = "DOCUMENTO_SOPORTE", NOTA_DEBITO = "NOTA_DEBITO", NOTA_AJUSTE_SOPORTE = "NOTA_AJUSTE_SOPORTE";
    /** Fase 16E: notas que corrigen otro documento (esperan a que el original esté aceptado). */
    private static final Set<String> NOTES = Set.of(NOTA_CREDITO, NOTA_DEBITO, NOTA_AJUSTE_SOPORTE);
    /** Modo fiscal (Fase 16A): toda venta lleva documento, o solo cuando el cliente lo pide. */
    public static final String OBLIGADO = "OBLIGADO", A_PEDIDO = "A_PEDIDO";

    /** Espera entre intentos automáticos (luego se queda en el último). */
    private static final Duration[] BACKOFF = {Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10),
            Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(6)};

    private final DocumentRepository docs;
    private final EventRepository events;
    private final FiscalProfileRepository profiles;
    private final ResolutionRepository resolutions;
    private final FiscalService fiscal;
    private final SaleRepository sales;
    private final SaleLineRepository saleLines;
    private final SaleReturnRepository saleReturns;
    private final SaleReturnLineRepository returnLines;
    private final CustomerRepository customers;
    private final ProductRepository products;
    private final GoodsReceiptRepository receipts;
    private final GoodsReceiptLineRepository receiptLines;
    private final SupplierRepository suppliers;
    private final com.tiendaposs.credit.CreditService.CreditEntryRepository creditEntries;
    private final PaymentRepository payments;
    private final Counters counters;
    private final EInvoiceProvider provider;
    private final AuditService audit;
    private final EntityManager em;
    private final com.tiendaposs.users.UserRepository users;
    private final org.springframework.security.crypto.password.PasswordEncoder encoder;
    private final TransactionTemplate tx;
    private final boolean async;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "einvoice-sender");
        t.setDaemon(true);
        return t;
    });

    public EInvoiceService(DocumentRepository docs, EventRepository events, FiscalProfileRepository profiles,
                           ResolutionRepository resolutions, FiscalService fiscal, SaleRepository sales, SaleLineRepository saleLines,
                           SaleReturnRepository saleReturns, SaleReturnLineRepository returnLines, CustomerRepository customers,
                           ProductRepository products, GoodsReceiptRepository receipts, GoodsReceiptLineRepository receiptLines,
                           SupplierRepository suppliers, com.tiendaposs.credit.CreditService.CreditEntryRepository creditEntries,
                           PaymentRepository payments, Counters counters, EInvoiceProvider provider, AuditService audit,
                           EntityManager em, com.tiendaposs.users.UserRepository users,
                           org.springframework.security.crypto.password.PasswordEncoder encoder, TransactionTemplate tx, @Value("${app.scheduler.enabled:true}") boolean async) {
        this.docs = docs;
        this.events = events;
        this.profiles = profiles;
        this.resolutions = resolutions;
        this.fiscal = fiscal;
        this.sales = sales;
        this.saleLines = saleLines;
        this.saleReturns = saleReturns;
        this.returnLines = returnLines;
        this.customers = customers;
        this.products = products;
        this.receipts = receipts;
        this.receiptLines = receiptLines;
        this.suppliers = suppliers;
        this.creditEntries = creditEntries;
        this.payments = payments;
        this.counters = counters;
        this.provider = provider;
        this.audit = audit;
        this.em = em;
        this.users = users;
        this.encoder = encoder;
        // Transacción propia: el envío corre después de confirmar la venta y sus cambios deben guardarse aparte.
        this.tx = new TransactionTemplate(tx.getTransactionManager());
        this.tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.async = async;
    }

    // =================================================================== Ganchos desde el POS

    /** ¿Esta tienda emite documentos electrónicos? */
    public boolean enabled(Long tenantId) {
        return profiles.findById(tenantId).map(p -> p.invoicingEnabled).orElse(false);
    }

    /** Modo fiscal de la tienda: OBLIGADO, A_PEDIDO o null si no factura. */
    public String mode(Long tenantId) {
        return profiles.findById(tenantId).filter(p -> p.invoicingEnabled).map(p -> p.invoicingMode).orElse(null);
    }

    /**
     * Venta cobrada (caja, cuenta abierta, pedido en línea o venta sincronizada sin internet). Se llama dentro de la
     * transacción de la venta. Si el cliente pidió factura y le faltan datos, la venta en línea se rechaza para que el
     * cajero los complete; la venta hecha sin internet nunca se rechaza: su factura queda BLOQUEADA hasta completarlos.
     * En modo A_PEDIDO la venta sin factura no genera documento: sale con comprobante interno (no es factura).
     */
    public void onSaleCompleted(Sale s) {
        FiscalProfile p = profiles.findById(s.tenantId).orElse(null);
        if (p == null || !p.invoicingEnabled) return;
        if (docs.findSaleDocument(s.id).isPresent()) return;
        // Fase 16B: una venta hecha antes del corte (sin internet, sincronizada después) no se factura.
        if (p.fiscalStartAt != null && s.completedAt != null && s.completedAt.isBefore(p.fiscalStartAt)) return;
        String type = POS;
        Customer c = s.customerId == null ? null : customers.findById(s.customerId).orElse(null);
        if (s.invoiceRequested) {
            List<String> missing = c == null ? List.of("cliente identificado") : com.tiendaposs.pos.PosCrudService.fiscalMissingOf(c);
            if (!missing.isEmpty() && !s.offline) {
                throw ApiException.field("customer", "Para la factura electrónica falta: " + String.join(", ", missing)
                        + ". Complétalo o cobra sin factura a nombre del cliente.");
            }
            type = FACTURA;
        } else if (A_PEDIDO.equals(p.invoicingMode)) {
            return;
        }
        ElectronicDocument d = newDocument(s.tenantId, p, type, "T" + s.tenantId + "-S" + s.id + "-" + type);
        d.saleId = s.id;
        d.issueDate = s.completedAt == null ? Instant.now() : s.completedAt;
        d.total = s.total;
        d.taxTotal = s.taxTotal;
        setBuyer(d, FACTURA.equals(type) ? c : null);
        applyPaymentForm(d, s);
        assignNumber(d, p);
        saveNew(d, "Creado por la venta #" + s.number + (s.offline ? " (hecha sin internet)" : ""));
    }

    private static void setBuyer(ElectronicDocument d, Customer c) {
        if (c != null) {
            d.customerId = c.id;
            d.customerName = c.name;
            d.customerIdType = c.docType;
            d.customerIdNumber = c.docNumber;
        } else {
            d.customerId = null;
            d.customerName = "Consumidor final";
            d.customerIdType = "CC";
            d.customerIdNumber = FiscalCodes.FINAL_CONSUMER_ID;
        }
    }

    /** Venta fiada: el documento sale "a crédito" con el vencimiento del cargo en la cartera del cliente. */
    private void applyPaymentForm(ElectronicDocument d, Sale s) {
        boolean credit = payments.findBySaleIdOrderByIdAsc(s.id).stream().anyMatch(x -> "FIADO".equals(x.method));
        if (!credit) {
            d.paymentForm = "CONTADO";
            d.dueDate = null;
            return;
        }
        d.paymentForm = "CREDITO";
        d.dueDate = creditEntries.findBySaleIdAndType(s.id, "CARGO").stream().map(e -> e.dueDate).filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(LocalDate.ofInstant(d.issueDate, ZoneId.of("America/Bogota")).plusDays(30));
    }

    // =================================================================== Refacturar (Fase 16A)

    /**
     * Factura a nombre de un cliente una venta que salió a consumidor final (o a nombre de otro): el documento anterior
     * se anula con nota crédito (o directamente si nunca fue aceptado) y se emite una factura nueva. Una factura nunca
     * "cambia de dueño": siempre nota crédito + documento nuevo. Solo dentro del mismo mes del documento anterior.
     */
    public ElectronicDocument reinvoice(Long tenantId, Long saleId, Long customerId) {
        Long id = tx.execute(st -> {
            Sale s = sales.findById(saleId).filter(x -> x.tenantId.equals(tenantId))
                    .orElseThrow(() -> ApiException.notFound("Venta no encontrada"));
            if (!"PAGADA".equals(s.status)) throw ApiException.badRequest("Solo se refacturan ventas cobradas y no anuladas.");
            FiscalProfile p = profiles.findById(tenantId).filter(x -> x.invoicingEnabled)
                    .orElseThrow(() -> ApiException.badRequest("Activa primero la facturación electrónica en Facturación."));
            if (p.fiscalStartAt != null && s.completedAt != null && s.completedAt.isBefore(p.fiscalStartAt)) {
                throw ApiException.badRequest("La venta #" + s.number + " es anterior a la fecha de corte de la facturación: no se puede facturar.");
            }
            Customer c = customers.findByIdAndTenantId(customerId, tenantId).orElseThrow(() -> ApiException.notFound("Cliente no encontrado"));
            if ("NUNCA".equals(c.invoicePreference)) {
                throw ApiException.badRequest(c.name + " está marcado para no recibir factura a su nombre. Cámbialo en su ficha.");
            }
            List<String> missing = com.tiendaposs.pos.PosCrudService.fiscalMissingOf(c);
            if (!missing.isEmpty()) throw ApiException.field("customerId", "A " + c.name + " le falta: " + String.join(", ", missing));
            if (!saleReturns.findBySaleIdOrderByIdAsc(s.id).isEmpty()) {
                throw ApiException.badRequest("La venta #" + s.number + " tiene devoluciones: no se puede refacturar completa.");
            }
            boolean credit = payments.findBySaleIdOrderByIdAsc(s.id).stream().anyMatch(x -> "FIADO".equals(x.method));
            if (credit && s.customerId != null && !s.customerId.equals(c.id)) {
                throw ApiException.badRequest("La venta #" + s.number + " es un fiado de " + s.customerName + ": solo se le puede facturar a él.");
            }
            ElectronicDocument old = docs.findSaleDocument(s.id).orElse(null);
            String why = "Refacturación de la venta #" + s.number + " a nombre de " + c.name;
            if (old != null) {
                if (c.id.equals(old.customerId) && !Set.of(RECHAZADO, BLOQUEADO, ANULADO).contains(old.status)) {
                    throw ApiException.badRequest("La venta ya tiene el documento " + old.fullNumber + " a nombre de " + c.name + ".");
                }
                if (EN_PROCESO.equals(old.status) || DESCONOCIDO.equals(old.status)) {
                    throw ApiException.badRequest("El documento " + old.fullNumber + " todavía se está validando: espera a que termine y vuelve a intentar.");
                }
                ZoneId zone = ZoneId.of("America/Bogota");
                java.time.YearMonth issued = java.time.YearMonth.from(old.issueDate.atZone(zone));
                if (ACEPTADO.equals(old.status) && !issued.equals(java.time.YearMonth.now(zone))) {
                    throw ApiException.badRequest("El documento " + old.fullNumber + " es de un mes anterior: solo se refactura dentro del mismo mes. "
                            + "Consulta con tu contador cómo corregirlo.");
                }
                old.superseded = true;
                docs.saveAndFlush(old);
                if (ACEPTADO.equals(old.status)) {
                    createNote(old, null, old.total, old.taxTotal, why);
                } else if (!ANULADO.equals(old.status)) {
                    old.status = ANULADO;
                    docs.save(old);
                    event(old, "ANULADO", null, "Reemplazado antes de ser aceptado. " + why + ".");
                }
            }
            s.customerId = c.id;
            s.customerName = c.name;
            s.customerDoc = c.docNumber;
            s.invoiceRequested = true;
            sales.save(s);
            int n = docs.findBySaleIdOrderByIdAsc(s.id).size();
            ElectronicDocument d = newDocument(tenantId, p, FACTURA, "T" + tenantId + "-S" + s.id + "-FACTURA-R" + n);
            d.saleId = s.id;
            d.issueDate = Instant.now();
            d.total = s.total;
            d.taxTotal = s.taxTotal;
            setBuyer(d, c);
            applyPaymentForm(d, s);
            assignNumber(d, p);
            saveNew(d, why + (old == null ? "" : " (reemplaza a " + old.fullNumber + ")"));
            if (old != null) {
                old.replacedById = d.id;
                docs.save(old);
            }
            audit.sensitive("VENTA_REFACTURADA", "SALE", s.id, why + (old == null ? "" : "; reemplaza " + old.fullNumber));
            return d.id;
        });
        return docs.findById(id).orElseThrow();
    }

    /** Venta anulada: nota crédito por el total si su documento ya fue (o pudo ser) aceptado; si nunca salió, se anula. */
    public void onSaleVoided(Sale s, String reason) {
        ElectronicDocument original = docs.findSaleDocument(s.id).orElse(null);
        if (original == null) return;
        if (Set.of(PENDIENTE, BLOQUEADO, RECHAZADO).contains(original.status)) {
            original.status = ANULADO;
            original.nextAttemptAt = Instant.now();
            docs.save(original);
            event(original, "ANULADO", null, "La venta se anuló antes de que el documento fuera aceptado: no se envía.");
            return;
        }
        if (!docs.findByOriginalIdOrderByIdAsc(original.id).stream().filter(n -> n.returnId == null).toList().isEmpty()) return;
        createNote(original, null, s.total, s.taxTotal, "Anulación de la venta #" + s.number + ": " + reason);
    }

    /** Devolución: nota crédito por lo devuelto, si la venta tiene documento electrónico. */
    public void onReturn(Sale s, SaleReturn r, BigDecimal amount, BigDecimal tax) {
        ElectronicDocument original = docs.findSaleDocument(s.id).orElse(null);
        if (original == null || ANULADO.equals(original.status)) return;
        createNote(original, r.id, amount, tax, "Devolución #" + r.number + " de la venta #" + s.number + ": " + r.reason);
    }

    private void createNote(ElectronicDocument original, Long returnId, BigDecimal amount, BigDecimal tax, String reason) {
        FiscalProfile p = profiles.findById(original.tenantId).orElseThrow();
        String ref = "T" + original.tenantId + "-NC-" + (returnId == null ? "D" + original.id : "R" + returnId);
        ElectronicDocument n = newDocument(original.tenantId, p, NOTA_CREDITO, ref);
        n.environment = original.environment;
        n.saleId = original.saleId;
        n.returnId = returnId;
        n.originalId = original.id;
        n.issueDate = Instant.now();
        n.total = amount;
        n.taxTotal = tax == null ? BigDecimal.ZERO : tax;
        n.customerName = original.customerName;
        n.customerIdType = original.customerIdType;
        n.customerIdNumber = original.customerIdNumber;
        n.customerId = original.customerId;
        n.reason = reason.length() > 300 ? reason.substring(0, 300) : reason;
        // Las notas llevan su propio consecutivo, sin resolución.
        n.prefix = "NC";
        n.number = counters.next(original.tenantId, "NOTA_CREDITO");
        n.fullNumber = n.prefix + n.number;
        saveNew(n, "Nota crédito sobre " + original.fullNumber + ". " + n.reason);
    }

    // =================================================================== Notas manuales (Fase 16E)

    /** Lo que queda de un documento: su total menos las notas que lo rebajan más las notas débito (sin las anuladas). */
    public BigDecimal remaining(ElectronicDocument original) {
        BigDecimal left = original.total;
        for (ElectronicDocument n : docs.findByOriginalIdOrderByIdAsc(original.id)) {
            if (ANULADO.equals(n.status)) continue;
            left = NOTA_DEBITO.equals(n.documentType) ? left.add(n.total) : left.subtract(n.total);
        }
        return left.max(BigDecimal.ZERO);
    }

    /** Crea la nota (la valida NoteService). El valor incluye el IVA de la tarifa indicada. */
    public ElectronicDocument issueManualNote(ElectronicDocument original, String type, String concept, String description,
                                              BigDecimal amount, BigDecimal taxRate, String reason, String settlement) {
        FiscalProfile p = profiles.findById(original.tenantId).orElseThrow();
        String counter = switch (type) {
            case NOTA_DEBITO -> "NOTA_DEBITO";
            case NOTA_AJUSTE_SOPORTE -> "NOTA_AJUSTE_SOPORTE";
            default -> "NOTA_CREDITO";
        };
        long number = counters.next(original.tenantId, counter);
        String prefix = switch (type) {
            case NOTA_DEBITO -> "ND";
            case NOTA_AJUSTE_SOPORTE -> "NAS";
            default -> "NC";
        };
        ElectronicDocument n = newDocument(original.tenantId, p, type, "T" + original.tenantId + "-" + prefix + "-M" + number);
        n.environment = original.environment;
        n.manual = true;
        n.saleId = original.saleId;
        n.originalId = original.id;
        n.issueDate = Instant.now();
        n.total = amount;
        n.taxTotal = taxRate.signum() == 0 ? BigDecimal.ZERO
                : amount.multiply(taxRate).divide(taxRate.add(BigDecimal.valueOf(100)), 2, RoundingMode.HALF_UP);
        n.customerName = original.customerName;
        n.customerIdType = original.customerIdType;
        n.customerIdNumber = original.customerIdNumber;
        n.customerId = original.customerId;
        n.noteConcept = concept;
        n.lineDescription = description.length() > 200 ? description.substring(0, 200) : description;
        n.lineTaxRate = taxRate;
        n.settlement = settlement;
        n.reason = reason.length() > 300 ? reason.substring(0, 300) : reason;
        n.prefix = prefix;
        n.number = number;
        n.fullNumber = prefix + number;
        String label = switch (type) {
            case NOTA_DEBITO -> "Nota débito";
            case NOTA_AJUSTE_SOPORTE -> "Nota de ajuste";
            default -> "Nota crédito";
        };
        saveNew(n, label + " sobre " + original.fullNumber + ". " + n.reason);
        audit.sensitive("NOTA_MANUAL", "EDOC", n.id, label + " " + n.fullNumber + " por " + com.tiendaposs.common.Money.fmt(amount)
                + " sobre " + original.fullNumber + ": " + n.reason);
        return n;
    }

    // =================================================================== Documento soporte (Fase 13E)

    /**
     * Recepción de mercancía de un proveedor NO obligado a facturar: el negocio emite el documento soporte para poder
     * soportar la compra. Se llama dentro de la transacción de la recepción, con el total ya calculado.
     */
    public void onReceipt(GoodsReceipt rec, Supplier s) {
        if (s.invoicesElectronically) return;
        FiscalProfile p = profiles.findById(rec.tenantId).orElse(null);
        if (p == null || !p.invoicingEnabled) return;
        if (docs.findByReceiptId(rec.id).isPresent()) return;
        ElectronicDocument d = newDocument(rec.tenantId, p, SOPORTE, "T" + rec.tenantId + "-DS-R" + rec.id);
        d.receiptId = rec.id;
        d.issueDate = rec.receivedAt == null ? Instant.now() : rec.receivedAt;
        d.total = rec.total;
        d.customerName = s.legalName != null ? s.legalName : s.name;
        d.customerIdType = s.docType;
        d.customerIdNumber = s.taxId;
        assignNumber(d, p);
        saveNew(d, "Creado por la recepción #" + rec.number + " de " + s.name);
    }

    /** Documento soporte de una recepción anterior (hecha antes de activar la facturación o de marcar al proveedor). */
    public ElectronicDocument createSupportDocument(Long tenantId, Long receiptId) {
        Long id = tx.execute(st -> {
            GoodsReceipt rec = receipts.findByIdAndTenantId(receiptId, tenantId).orElseThrow(() -> ApiException.notFound("Recepción no encontrada"));
            Supplier s = suppliers.findById(rec.supplierId).orElseThrow();
            if (s.invoicesElectronically) {
                throw ApiException.badRequest(s.name + " está marcado como obligado a facturar: su compra se soporta con la factura que te envía, "
                        + "no con documento soporte. Si no factura, cámbialo en la ficha del proveedor.");
            }
            if (!enabled(tenantId)) throw ApiException.badRequest("Activa primero la facturación electrónica en Facturación.");
            ElectronicDocument existing = docs.findByReceiptId(rec.id).orElse(null);
            if (existing != null) {
                throw ApiException.badRequest("La recepción #" + rec.number + " ya tiene el documento soporte " + existing.fullNumber + ".");
            }
            onReceipt(rec, s);
            return docs.findByReceiptId(rec.id).orElseThrow().id;
        });
        return docs.findById(id).orElseThrow();
    }

    // =================================================================== Documento de prueba y activación

    public ElectronicDocument createTest(Long tenantId) {
        FiscalProfile p = profiles.findById(tenantId).orElseThrow(() -> ApiException.badRequest("Completa primero los datos fiscales"));
        if (!"PRUEBAS".equals(p.environment)) throw ApiException.badRequest("El documento de prueba se emite en el ambiente de pruebas.");
        ElectronicDocument d = newDocument(tenantId, p, POS, "T" + tenantId + "-TEST-" + UUID.randomUUID());
        d.test = true;
        d.issueDate = Instant.now();
        d.total = new BigDecimal("1000.00");
        d.customerName = "Consumidor final";
        d.customerIdType = "CC";
        d.customerIdNumber = FiscalCodes.FINAL_CONSUMER_ID;
        assignNumber(d, p);
        saveNew(d, "Documento de prueba solicitado por " + userName());
        return d;
    }

    public boolean hasAcceptedIn(Long tenantId, String environment) {
        return docs.existsByTenantIdAndEnvironmentAndStatus(tenantId, environment, ACEPTADO);
    }

    public void setEnabled(Long tenantId, boolean on, String password) {
        FiscalProfile p = profiles.findById(tenantId).orElseThrow(() -> ApiException.badRequest("Completa primero los datos fiscales"));
        if (!on && p.invoicingEnabled) {
            // Fase 16B: apagar la facturación es del dueño y con su contraseña (las facturas emitidas no se tocan).
            var me = CurrentUser.get();
            var user = users.findById(me.userId()).orElseThrow();
            if (!me.owner()) throw ApiException.forbidden("Solo el dueño puede desactivar la facturación electrónica.");
            if (password == null || !encoder.matches(password, user.getPasswordHash())) {
                throw ApiException.field("password", "contraseña incorrecta");
            }
        }
        if (on) {
            if (!fiscal.readiness().ready()) throw ApiException.badRequest("Completa todos los pasos del asistente antes de activar la facturación.");
            if ("PRODUCCION".equals(p.environment) && provider.simulated()) {
                throw ApiException.badRequest("Ecomerxio POS todavía no tiene un proveedor tecnológico real conectado: "
                        + "por ahora la facturación solo se activa en el ambiente de pruebas.");
            }
        }
        p.invoicingEnabled = on;
        // Fase 16B: fecha de corte. Se fija la primera vez y otra vez al pasar de pruebas a producción.
        if (on && (p.fiscalStartAt == null || ("PRODUCCION".equals(p.environment) && !"PRODUCCION".equals(p.fiscalStartEnvironment)))) {
            p.fiscalStartAt = Instant.now();
            p.fiscalStartEnvironment = p.environment;
            audit.sensitive("FACTURACION_CORTE", "TENANT", tenantId, "Fecha de corte de la facturación: " + p.fiscalStartAt
                    + " (" + p.environment.toLowerCase() + "). Las ventas anteriores no se facturan.");
        }
        profiles.save(p);
        audit.sensitive(on ? "FACTURACION_ACTIVADA" : "FACTURACION_DESACTIVADA", "TENANT", tenantId,
                (on ? "Activó" : "Desactivó") + " la facturación electrónica (" + p.environment.toLowerCase() + ")");
    }

    /** Fase 16A: toda venta lleva documento (OBLIGADO) o solo cuando el cliente lo pide (A_PEDIDO). */
    public void setMode(Long tenantId, String mode) {
        if (!OBLIGADO.equals(mode) && !A_PEDIDO.equals(mode)) throw ApiException.field("mode", "elige OBLIGADO o A_PEDIDO");
        FiscalProfile p = profiles.findById(tenantId).orElseThrow(() -> ApiException.badRequest("Completa primero los datos fiscales"));
        if (mode.equals(p.invoicingMode)) return;
        p.invoicingMode = mode;
        profiles.save(p);
        audit.sensitive("FACTURACION_MODO", "TENANT", tenantId, OBLIGADO.equals(mode)
                ? "Cambió a «toda venta lleva documento» (obligado a facturar)"
                : "Cambió a «factura solo cuando el cliente la pide» (no obligado)");
    }

    public record ActivationPreview(long salesLast30Days, long invoiceRequestsLast30Days, long estimatedDocumentsPerMonth,
                                    long salesBeforeCutoff, java.time.Instant cutoffAt, boolean cutoffWillMove, String mode) {
    }

    /** Fase 16B: lo que pasa al activar (cuántos documentos gastará al mes y cuántas ventas quedan antes del corte). */
    public ActivationPreview activationPreview(Long tenantId) {
        FiscalProfile p = profiles.findById(tenantId).orElseThrow(() -> ApiException.badRequest("Completa primero los datos fiscales"));
        Instant since = Instant.now().minus(Duration.ofDays(30));
        Object[] row = (Object[]) em.createNativeQuery("""
                        select count(*), count(*) filter (where invoice_requested) from sales
                        where tenant_id = ?1 and status = 'PAGADA' and completed_at >= ?2""")
                .setParameter(1, tenantId).setParameter(2, java.sql.Timestamp.from(since)).getSingleResult();
        long sales30 = ((Number) row[0]).longValue(), requested30 = ((Number) row[1]).longValue();
        boolean moves = p.fiscalStartAt == null || ("PRODUCCION".equals(p.environment) && !"PRODUCCION".equals(p.fiscalStartEnvironment));
        Instant cut = moves ? Instant.now() : p.fiscalStartAt;
        long before = ((Number) em.createNativeQuery("select count(*) from sales where tenant_id = ?1 and status = 'PAGADA' and completed_at < ?2")
                .setParameter(1, tenantId).setParameter(2, java.sql.Timestamp.from(cut)).getSingleResult()).longValue();
        long estimate = A_PEDIDO.equals(p.invoicingMode) ? requested30 : sales30;
        return new ActivationPreview(sales30, requested30, estimate, before, moves ? null : p.fiscalStartAt, moves, p.invoicingMode);
    }

    /** Fase 16B: mostrar u ocultar en informes las ventas anteriores al corte (solo el dueño). */
    public void setHidePreCutoff(Long tenantId, boolean hide) {
        if (!CurrentUser.get().owner()) throw ApiException.forbidden("Solo el dueño puede cambiar esto.");
        FiscalProfile p = profiles.findById(tenantId).orElseThrow(() -> ApiException.badRequest("Completa primero los datos fiscales"));
        p.hidePreCutoff = hide;
        profiles.save(p);
        audit.sensitive("FACTURACION_CORTE_VISTA", "TENANT", tenantId, hide
                ? "Ocultó en informes las ventas anteriores a la fecha de corte"
                : "Mostró en informes las ventas anteriores a la fecha de corte");
    }

    // =================================================================== Creación y numeración

    private ElectronicDocument newDocument(Long tenantId, FiscalProfile p, String type, String externalRef) {
        ElectronicDocument d = new ElectronicDocument();
        d.tenantId = tenantId;
        d.documentType = type;
        d.environment = p.environment;
        d.provider = provider.code();
        d.externalRef = externalRef;
        d.status = PENDIENTE;
        d.nextAttemptAt = Instant.now();
        return d;
    }

    /** Toma el siguiente número de la resolución vigente de forma atómica; sin resolución, el documento queda BLOQUEADO. */
    private void assignNumber(ElectronicDocument d, FiscalProfile p) {
        LocalDate today = LocalDate.now(ZoneId.of("America/Bogota"));
        List<NumberingResolution> candidates = resolutions.findByTenantIdAndActiveTrue(d.tenantId).stream()
                .filter(r -> r.documentType.equals(d.documentType) && r.environment.equals(d.environment))
                .filter(r -> !today.isBefore(r.validFrom) && !today.isAfter(r.validTo) && r.remaining() > 0)
                .sorted(Comparator.comparing((NumberingResolution r) -> r.validTo)).toList();
        for (NumberingResolution r : candidates) {
            @SuppressWarnings("unchecked")
            List<Number> got = em.createNativeQuery("update numbering_resolutions set next_number = next_number + 1, updated_at = now() "
                            + "where id = ?1 and next_number <= range_to returning next_number - 1")
                    .setParameter(1, r.id).getResultList();
            if (!got.isEmpty()) {
                d.resolutionId = r.id;
                d.prefix = r.prefix;
                d.number = got.get(0).longValue();
                d.fullNumber = (r.prefix == null ? "" : r.prefix) + d.number;
                return;
            }
        }
        d.number = 0;
        d.fullNumber = "SIN NÚMERO";
        d.status = BLOQUEADO;
        d.lastErrorCode = "CONFIG_RESOLUCION";
        d.lastErrorMessage = "No hay una resolución de numeración vigente y con números disponibles para "
                + FiscalCodes.DOCUMENT_TYPES.get(d.documentType) + " en el ambiente de " + d.environment.toLowerCase()
                + ". Regístrala en Facturación → Resoluciones y reintenta.";
    }

    private void saveNew(ElectronicDocument d, String message) {
        docs.save(d);
        event(d, "CREADO", d.lastErrorCode, message);
        if (BLOQUEADO.equals(d.status)) event(d, "BLOQUEADO", d.lastErrorCode, d.lastErrorMessage);
        afterCommit(d.id);
    }

    /** Apenas termine la transacción de la venta se intenta enviar (en segundo plano, sin demorar la caja). */
    private void afterCommit(Long id) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submit(id);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(id);
            }
        });
    }

    private void submit(Long id) {
        if (async) executor.submit(() -> safeProcess(id));
        else safeProcess(id);
    }

    // =================================================================== Cola y procesamiento

    @Scheduled(initialDelay = 20_000, fixedDelay = 15_000)
    public void processQueue() {
        if (!async) return;
        processDue();
    }

    /** Procesa lo pendiente cuya hora llegó. Devuelve cuántos documentos procesó. */
    public int processDue() {
        int n = 0;
        for (Long id : docs.dueIds(Instant.now(), 25)) {
            if (safeProcess(id)) n++;
        }
        return n;
    }

    private boolean safeProcess(Long id) {
        try {
            return Boolean.TRUE.equals(tx.execute(st -> process(id)));
        } catch (RuntimeException e) {
            log.warn("No se pudo procesar el documento electrónico {}: {}", id, e.toString());
            return false;
        }
    }

    /** Un paso del documento: consultar o enviar según su estado. Corre en su propia transacción con la fila bloqueada. */
    private boolean process(Long id) {
        ElectronicDocument d = docs.lockForProcessing(id).orElse(null);
        if (d == null || !Set.of(PENDIENTE, EN_PROCESO, DESCONOCIDO).contains(d.status)) return false;

        if ("PRODUCCION".equals(d.environment) && provider.simulated()) {
            block(d, "CONFIG_PROVEEDOR", "No hay un proveedor tecnológico real conectado: con el simulador no se emite en producción.");
            return true;
        }
        if (NOTES.contains(d.documentType) && d.originalId != null) {
            ElectronicDocument original = docs.findById(d.originalId).orElseThrow();
            if (ANULADO.equals(original.status)) {
                d.status = ANULADO;
                docs.save(d);
                event(d, "ANULADO", null, "El documento original nunca fue aceptado: la nota no hace falta.");
                return true;
            }
            if (!ACEPTADO.equals(original.status)) {
                d.nextAttemptAt = Instant.now().plus(Duration.ofMinutes(1));
                docs.save(d);
                return false;
            }
        }

        d.attempts++;
        Result result;
        try {
            if (EN_PROCESO.equals(d.status) || DESCONOCIDO.equals(d.status)) {
                result = provider.query(d.externalRef);
                event(d, "CONSULTA", null, "Consulta al proveedor: " + describe(result));
                if (result.outcome() == Outcome.NOT_FOUND) {
                    // Nunca le llegó: ahora sí se envía (con la misma referencia y el mismo número).
                    result = sendValidated(d);
                    if (result == null) return true;
                }
            } else {
                result = sendValidated(d);
                if (result == null) return true;
            }
        } catch (ProviderUnavailableException e) {
            d.status = DESCONOCIDO;
            d.lastErrorCode = "SIN_RESPUESTA";
            d.lastErrorMessage = "El proveedor no respondió. Se consultará su estado antes de volver a enviarlo.";
            d.nextAttemptAt = Instant.now().plus(backoff(d.attempts));
            docs.save(d);
            event(d, "SIN_RESPUESTA", "SIN_RESPUESTA", e.getMessage());
            return true;
        }
        apply(d, result);
        return true;
    }

    /** Valida y envía; null si quedó BLOQUEADO por la validación propia. */
    private Result sendValidated(ElectronicDocument d) throws ProviderUnavailableException {
        if (d.number == 0) {
            FiscalProfile p = profiles.findById(d.tenantId).orElseThrow();
            assignNumber(d, p);
            if (BLOQUEADO.equals(d.status)) {
                docs.save(d);
                event(d, "BLOQUEADO", d.lastErrorCode, d.lastErrorMessage);
                return null;
            }
        }
        Payload payload;
        List<String[]> problems = new ArrayList<>();
        payload = buildPayload(d, problems);
        if (!problems.isEmpty()) {
            block(d, problems.get(0)[0], "Antes de enviarlo hay que corregir: "
                    + String.join("; ", problems.stream().map(x -> x[1]).toList()) + ".");
            return null;
        }
        Result r = provider.send(payload);
        event(d, "ENVIO", null, "Enviado al proveedor (" + provider.code() + "): " + describe(r));
        return r;
    }

    private void apply(ElectronicDocument d, Result r) {
        switch (r.outcome()) {
            case ACCEPTED -> {
                d.status = ACEPTADO;
                d.cufe = r.cufe();
                d.qrData = r.qrData();
                d.trackId = r.trackId();
                d.acceptedAt = Instant.now();
                d.lastErrorCode = null;
                d.lastErrorMessage = null;
                event(d, "ACEPTADO", null, "Validado" + (provider.simulated() ? " (simulado, sin validez)" : "") + ".");
            }
            case REJECTED -> {
                d.status = RECHAZADO;
                d.trackId = r.trackId();
                ProviderError first = r.errors().isEmpty() ? new ProviderError("RECHAZO", "Rechazado sin detalle") : r.errors().get(0);
                d.lastErrorCode = first.code();
                d.lastErrorMessage = r.errors().stream().map(e -> e.code() + ": " + e.message()).reduce((a, b) -> a + " | " + b).orElse(first.message());
                event(d, "RECHAZADO", first.code(), d.lastErrorMessage);
            }
            case PROCESSING -> {
                d.status = EN_PROCESO;
                d.trackId = r.trackId();
                d.nextAttemptAt = Instant.now().plus(backoff(Math.max(0, d.attempts - 1)));
                event(d, "EN_PROCESO", null, "El proveedor lo está procesando; se consultará más tarde.");
            }
            case NOT_FOUND -> {
                d.status = PENDIENTE;
                d.nextAttemptAt = Instant.now().plus(backoff(d.attempts));
            }
        }
        docs.save(d);
    }

    private void block(ElectronicDocument d, String code, String message) {
        d.status = BLOQUEADO;
        d.lastErrorCode = code;
        d.lastErrorMessage = message.length() > 500 ? message.substring(0, 497) + "…" : message;
        docs.save(d);
        event(d, "BLOQUEADO", code, message);
    }

    private static Duration backoff(int attempts) {
        return BACKOFF[Math.min(Math.max(attempts - 1, 0), BACKOFF.length - 1)];
    }

    // =================================================================== Acciones manuales

    /** Reintentar después de corregir (rechazado o bloqueado) o forzar la consulta de uno desconocido. */
    public ElectronicDocument retry(Long tenantId, Long id) {
        ElectronicDocument d = tx.execute(st -> {
            ElectronicDocument x = docs.findByIdAndTenantId(id, tenantId).orElseThrow(() -> ApiException.notFound("Documento no encontrado"));
            if (!Set.of(RECHAZADO, BLOQUEADO, DESCONOCIDO, EN_PROCESO, PENDIENTE).contains(x.status)) {
                throw ApiException.badRequest("Este documento ya no se puede reintentar (" + x.status.toLowerCase() + ").");
            }
            // Un rechazado o bloqueado se vuelve a armar con los datos corregidos; uno desconocido se consulta primero.
            if (RECHAZADO.equals(x.status) || BLOQUEADO.equals(x.status)) x.status = PENDIENTE;
            x.nextAttemptAt = Instant.now();
            docs.save(x);
            event(x, "REINTENTO_MANUAL", null, "Reintento pedido por " + userName());
            return x;
        });
        safeProcess(d.id);
        return docs.findById(d.id).orElseThrow();
    }

    public record BulkResult(int requested, int accepted, int stillWithProblems, List<DocumentDto> documents) {
    }

    /** Reintenta varios documentos (los de otra tienda o ya finales se ignoran). */
    public BulkResult retryMany(Long tenantId, List<Long> ids) {
        List<DocumentDto> out = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(ids)) {
            ElectronicDocument d = docs.findByIdAndTenantId(id, tenantId).orElse(null);
            if (d == null || !Set.of(RECHAZADO, BLOQUEADO, DESCONOCIDO, EN_PROCESO, PENDIENTE).contains(d.status)) continue;
            out.add(dto(retry(tenantId, id)));
        }
        int ok = (int) out.stream().filter(x -> ACEPTADO.equals(x.status())).count();
        return new BulkResult(ids.size(), ok, out.size() - ok, out);
    }

    /** Para las alertas: documentos con problemas (rechazados, bloqueados o sin respuesta hace más de una hora). */
    public long problemCount(Long tenantId) {
        Instant hourAgo = Instant.now().minus(Duration.ofHours(1));
        return docs.findByTenantIdAndStatusIn(tenantId, List.of(RECHAZADO, BLOQUEADO, DESCONOCIDO)).stream()
                .filter(d -> !DESCONOCIDO.equals(d.status) || d.createdAt.isBefore(hourAgo)).count();
    }

    // =================================================================== Consulta

    public record DocumentDto(Long id, String documentType, String documentLabel, String fullNumber, String status, String environment,
                              String provider, boolean simulated, boolean test, Long saleId, Long saleNumber, Long returnId,
                              String originalNumber, String customerName, String customerIdNumber, BigDecimal total, BigDecimal taxTotal,
                              Instant issueDate, String cufe, String qrData, int attempts, Instant nextAttemptAt,
                              String lastErrorCode, String lastErrorMessage, String reason, Instant acceptedAt, Instant createdAt,
                              /** Explicación del error, causa, acción y dónde corregirlo (Fase 13C). */ ErrorCatalog.Help help,
                              /** Documento soporte: la recepción y el proveedor (Fase 13E). */ Long receiptId, Long receiptNumber,
                              Long supplierId,
                              /** Fase 16A: crédito (fiado) y refacturación. */ String paymentForm, LocalDate dueDate,
                              boolean superseded, String replacedByNumber,
                              /** Fase 16E: nota manual, su concepto, cómo se arregló y cuánto queda del documento. */
                              boolean manual, String noteConcept, String settlement, BigDecimal remaining) {
    }

    public record EventDto(String type, String statusAfter, String code, String message, String userName, Instant createdAt) {
    }

    public record DocumentDetail(DocumentDto document, List<EventDto> events, List<DocumentDto> notes) {
    }

    public record Summary(boolean enabled, String environment, String provider, boolean simulated, Map<String, Long> byStatus,
                          String mode, /** Fase 16B */ Instant fiscalStartAt, boolean hidePreCutoff) {
    }

    public DocumentDto dto(ElectronicDocument d) {
        Long saleNumber = d.saleId == null ? null : sales.findById(d.saleId).map(s -> s.number).orElse(null);
        String original = d.originalId == null ? null : docs.findById(d.originalId).map(o -> o.fullNumber).orElse(null);
        String label = switch (d.documentType) {
            case NOTA_CREDITO -> "Nota crédito";
            case NOTA_DEBITO -> "Nota débito";
            case NOTA_AJUSTE_SOPORTE -> "Nota de ajuste al documento soporte";
            default -> FiscalCodes.DOCUMENT_TYPES.get(d.documentType);
        };
        GoodsReceipt rec = d.receiptId == null ? null : receipts.findById(d.receiptId).orElse(null);
        return new DocumentDto(d.id, d.documentType, label, d.fullNumber, d.status, d.environment, d.provider,
                "SIMULADO".equals(d.provider), d.test, d.saleId, saleNumber, d.returnId, original, d.customerName, d.customerIdNumber,
                d.total, d.taxTotal, d.issueDate, d.cufe, d.qrData, d.attempts, d.nextAttemptAt, d.lastErrorCode, d.lastErrorMessage,
                d.reason, d.acceptedAt, d.createdAt,
                Set.of(RECHAZADO, BLOQUEADO, DESCONOCIDO).contains(d.status) ? ErrorCatalog.help(d.lastErrorCode, d.status) : null,
                d.receiptId, rec == null ? null : rec.number, rec == null ? null : rec.supplierId,
                d.paymentForm, d.dueDate, d.superseded,
                d.replacedById == null ? null : docs.findById(d.replacedById).map(x -> x.fullNumber).orElse(null),
                d.manual, d.noteConcept, d.settlement, NOTES.contains(d.documentType) ? null : remaining(d));
    }

    /** El documento soporte de una recepción (null si no tiene). */
    public DocumentDto forReceipt(Long tenantId, Long receiptId) {
        return docs.findByReceiptId(receiptId).filter(d -> d.tenantId.equals(tenantId)).map(this::dto).orElse(null);
    }

    public List<DocumentDto> list(Long tenantId, String status, int limit) {
        return docs.list(tenantId, status == null || status.isBlank() ? null : status,
                org.springframework.data.domain.PageRequest.of(0, Math.min(Math.max(limit, 1), 500))).stream().map(this::dto).toList();
    }

    public DocumentDetail detail(Long tenantId, Long id) {
        ElectronicDocument d = docs.findByIdAndTenantId(id, tenantId).orElseThrow(() -> ApiException.notFound("Documento no encontrado"));
        return new DocumentDetail(dto(d), events.findByDocumentIdOrderByIdAsc(d.id).stream()
                .map(e -> new EventDto(e.type, e.statusAfter, e.code, e.message, e.userName, e.createdAt)).toList(),
                docs.findByOriginalIdOrderByIdAsc(d.id).stream().map(this::dto).toList());
    }

    /** El documento de una venta, para el comprobante (null si no tiene). */
    public DocumentDto forSale(Long tenantId, Long saleId) {
        return docs.findSaleDocument(saleId).filter(d -> d.tenantId.equals(tenantId)).map(this::dto).orElse(null);
    }

    public Summary summary(Long tenantId) {
        FiscalProfile p = profiles.findById(tenantId).orElse(null);
        Map<String, Long> by = new LinkedHashMap<>();
        for (Object[] row : docs.countByStatus(tenantId)) by.put((String) row[0], ((Number) row[1]).longValue());
        return new Summary(p != null && p.invoicingEnabled, p == null ? "PRUEBAS" : p.environment, provider.code(), provider.simulated(), by,
                p == null ? OBLIGADO : p.invoicingMode, p == null ? null : p.fiscalStartAt, p == null || p.hidePreCutoff);
    }

    // =================================================================== Armado del documento

    /** Problemas: {código del catálogo, texto}. */
    private Payload buildPayload(ElectronicDocument d, List<String[]> problems) {
        FiscalProfile p = profiles.findById(d.tenantId).orElseThrow();
        List<String> issuerMissing = fiscal.missingProfileData(p);
        if (!issuerMissing.isEmpty()) problems.add(new String[]{"VAL_EMISOR", "datos fiscales del negocio (" + String.join(", ", issuerMissing) + ")"});
        Party issuer = new Party(p.idType, FiscalCodes.ID_TYPES.get(p.idType), p.idNumber, p.dv, p.legalName, p.personType,
                p.responsibilities == null ? List.of() : List.of(p.responsibilities.split(",")), FiscalCodes.tributeCode(p.ivaResponsible),
                p.invoicingEmail, p.address,
                p.municipalityCode);

        if (SOPORTE.equals(d.documentType) || NOTA_AJUSTE_SOPORTE.equals(d.documentType)) return supportPayload(d, issuer, problems);

        Sale sale = d.saleId == null ? null : sales.findById(d.saleId).orElse(null);
        Party buyer = finalConsumer();
        boolean finalConsumer = true;
        if (FACTURA.equals(d.documentType) || ((NOTA_CREDITO.equals(d.documentType) || NOTA_DEBITO.equals(d.documentType))
                && !FiscalCodes.FINAL_CONSUMER_ID.equals(d.customerIdNumber))) {
            Long buyerId = d.customerId != null ? d.customerId : sale == null ? null : sale.customerId;
            Customer c = buyerId == null ? null : customers.findById(buyerId).orElse(null);
            List<String> missing = c == null ? List.of("cliente identificado") : com.tiendaposs.pos.PosCrudService.fiscalMissingOf(c);
            if (!missing.isEmpty()) problems.add(new String[]{"VAL_CLIENTE", "datos del cliente (" + String.join(", ", missing) + ")"});
            if (c != null) {
                buyer = new Party(c.docType, FiscalCodes.ID_TYPES.get(c.docType), c.docNumber, c.dv, c.name, c.personType,
                        c.fiscalResponsibilities == null ? List.of("R-99-PN") : List.of(c.fiscalResponsibilities.split(",")),
                        FiscalCodes.tributeCode(c.ivaResponsible), c.invoiceEmail != null ? c.invoiceEmail : c.email, c.address,
                        c.municipalityCode);
                finalConsumer = false;
            }
        }

        List<Line> lines = new ArrayList<>();
        if (d.test) {
            lines.add(new Line("Documento de prueba", BigDecimal.ONE, "94", d.total, BigDecimal.ZERO, "IVA", BigDecimal.ZERO,
                    "EXCLUIDO", BigDecimal.ZERO, d.total));
        } else if (d.manual) {
            lines.add(manualLine(d));
        } else if (NOTA_CREDITO.equals(d.documentType) && d.returnId != null) {
            SaleReturn r = saleReturns.findById(d.returnId).orElseThrow();
            Map<Long, SaleLine> byId = new HashMap<>();
            saleLines.findBySaleIdOrderBySortOrderAscIdAsc(r.saleId).forEach(l -> byId.put(l.id, l));
            for (SaleReturnLine rl : returnLines.findByReturnIdIn(List.of(r.id))) {
                SaleLine l = byId.get(rl.saleLineId);
                if (l != null) lines.add(line(l, rl.quantity, rl.amount, problems));
            }
        } else if (sale != null) {
            for (SaleLine l : saleLines.findBySaleIdOrderBySortOrderAscIdAsc(sale.id)) lines.add(line(l, l.quantity, l.lineTotal, problems));
        }
        if (lines.isEmpty()) problems.add(new String[]{"VAL_SIN_LINEAS", "el documento no tiene líneas"});

        String originalNumber = null, originalCufe = null;
        if (d.originalId != null) {
            ElectronicDocument o = docs.findById(d.originalId).orElseThrow();
            originalNumber = o.fullNumber;
            originalCufe = o.cufe;
        }
        String resolutionNumber = d.resolutionId == null ? null : resolutions.findById(d.resolutionId).map(r -> r.resolutionNumber).orElse(null);
        return new Payload(d.externalRef, d.documentType, d.environment, d.fullNumber, d.prefix, d.number, resolutionNumber,
                d.issueDate, issuer, buyer, finalConsumer, lines, d.taxTotal, d.total, originalNumber, originalCufe, d.reason,
                d.paymentForm, d.dueDate, d.noteConcept);
    }

    /** La única línea de una nota manual: el valor incluye el IVA de su tarifa. */
    private static Line manualLine(ElectronicDocument d) {
        BigDecimal rate = d.lineTaxRate == null ? BigDecimal.ZERO : d.lineTaxRate;
        BigDecimal base = d.total.subtract(d.taxTotal);
        return new Line(d.lineDescription == null ? "Ajuste" : d.lineDescription, BigDecimal.ONE, "94", base, BigDecimal.ZERO, "IVA", rate,
                rate.signum() > 0 ? "GRAVADO" : "EXCLUIDO", d.taxTotal, d.total);
    }

    /** Una línea; con cantidad o total parcial (devoluciones) se prorratea el impuesto. */
    private Line line(SaleLine l, BigDecimal qty, BigDecimal total, List<String[]> problems) {
        Product p = products.findById(l.productId).orElse(null);
        String taxClass = l.taxRate != null && l.taxRate.signum() > 0 ? "GRAVADO" : p == null ? null : p.taxClass;
        if (taxClass == null) problems.add(new String[]{"VAL_PRODUCTO", "clasificación tributaria de «" + l.productName + "» (IVA 0 %: exento o excluido)"});
        String unit = p == null ? "94" : FiscalCodes.UNITS.getOrDefault(p.unit, "94");
        BigDecimal share = l.lineTotal == null || l.lineTotal.signum() == 0 ? BigDecimal.ONE
                : total.divide(l.lineTotal, 6, RoundingMode.HALF_UP);
        BigDecimal tax = l.tax == null ? BigDecimal.ZERO : l.tax.multiply(share).setScale(2, RoundingMode.HALF_UP);
        String description = l.productName + (l.presentationName == null || l.presentationName.isBlank() ? "" : " (" + l.presentationName + ")");
        return new Line(description, qty, unit, l.unitPrice, l.discount == null ? BigDecimal.ZERO : l.discount.multiply(share),
                l.taxName, l.taxRate, taxClass, tax, total);
    }

    /**
     * Documento soporte: lo emite el negocio (issuer) y la contraparte es el VENDEDOR, el proveedor no obligado a
     * facturar (va en el lugar de {@code buyer}). Sin IVA: quien no factura no es responsable de IVA.
     */
    private Payload supportPayload(ElectronicDocument d, Party issuer, List<String[]> problems) {
        if (NOTA_AJUSTE_SOPORTE.equals(d.documentType)) {
            // Fase 16E: la nota de ajuste lleva una sola línea y referencia al documento soporte original.
            ElectronicDocument orig = docs.findById(d.originalId).orElseThrow();
            Supplier s = suppliers.findById(receipts.findById(orig.receiptId).orElseThrow().supplierId).orElseThrow();
            Party seller = new Party(s.docType, FiscalCodes.ID_TYPES.get(s.docType), s.taxId, s.dv, s.legalName != null ? s.legalName : s.name,
                    s.personType, List.of("R-99-PN"), FiscalCodes.tributeCode(false), s.email, s.address, s.municipalityCode);
            ElectronicDocument o = orig;
            String resolutionNumber = o.resolutionId == null ? null : resolutions.findById(o.resolutionId).map(r -> r.resolutionNumber).orElse(null);
            return new Payload(d.externalRef, d.documentType, d.environment, d.fullNumber, d.prefix, d.number, resolutionNumber,
                    d.issueDate, issuer, seller, false, List.of(manualLine(d)), BigDecimal.ZERO, d.total, o.fullNumber, o.cufe, d.reason,
                    "CONTADO", null, d.noteConcept);
        }
        GoodsReceipt rec = receipts.findById(d.receiptId).orElseThrow();
        Supplier s = suppliers.findById(rec.supplierId).orElseThrow();
        List<String> missing = SupplierService.supportDocumentMissing(s);
        if (!missing.isEmpty()) problems.add(new String[]{"VAL_PROVEEDOR", "datos del proveedor " + s.name + " (" + String.join(", ", missing) + ")"});
        Party seller = new Party(s.docType, FiscalCodes.ID_TYPES.get(s.docType), s.taxId, s.dv, s.legalName != null ? s.legalName : s.name,
                s.personType, List.of("R-99-PN"), FiscalCodes.tributeCode(false), s.email, s.address, s.municipalityCode);
        List<Line> lines = new ArrayList<>();
        List<GoodsReceiptLine> rls = receiptLines.findByReceiptIdIn(List.of(rec.id));
        Map<Long, Product> ps = new HashMap<>();
        products.findAllById(rls.stream().map(l -> l.productId).distinct().toList()).forEach(p -> ps.put(p.id, p));
        for (GoodsReceiptLine l : rls) {
            if (l.unitsReceived.signum() <= 0) continue;
            Product p = ps.get(l.productId);
            BigDecimal total = l.unitsReceived.multiply(l.unitCost).setScale(2, RoundingMode.HALF_UP);
            lines.add(new Line(p == null ? "Producto" : p.name, l.unitsReceived, p == null ? "94" : FiscalCodes.UNITS.getOrDefault(p.unit, "94"),
                    l.unitCost, BigDecimal.ZERO, "IVA", BigDecimal.ZERO, "NO_RESPONSABLE", BigDecimal.ZERO, total));
        }
        if (lines.isEmpty()) problems.add(new String[]{"VAL_SIN_LINEAS", "la recepción no tiene productos recibidos"});
        String resolutionNumber = d.resolutionId == null ? null : resolutions.findById(d.resolutionId).map(r -> r.resolutionNumber).orElse(null);
        return new Payload(d.externalRef, d.documentType, d.environment, d.fullNumber, d.prefix, d.number, resolutionNumber,
                d.issueDate, issuer, seller, false, lines, BigDecimal.ZERO, d.total, null, null, null, "CONTADO", null, null);
    }

    private static Party finalConsumer() {
        return new Party("CC", FiscalCodes.ID_TYPES.get("CC"), FiscalCodes.FINAL_CONSUMER_ID, null, "Consumidor final", "NATURAL",
                List.of("R-99-PN"), FiscalCodes.tributeCode(null), null, null, null);
    }

    // =================================================================== utilidades

    private void event(ElectronicDocument d, String type, String code, String message) {
        ElectronicDocumentEvent e = new ElectronicDocumentEvent();
        e.tenantId = d.tenantId;
        e.documentId = d.id;
        e.type = type;
        e.statusAfter = d.status;
        e.code = code;
        e.message = message == null ? null : message.length() > 1000 ? message.substring(0, 997) + "…" : message;
        e.userName = userName();
        events.save(e);
    }

    private static String userName() {
        var u = CurrentUser.getOrNull();
        return u == null ? "Sistema" : u.fullName();
    }

    private static String describe(Result r) {
        return switch (r.outcome()) {
            case ACCEPTED -> "aceptado";
            case REJECTED -> "rechazado" + (r.errors().isEmpty() ? "" : " (" + r.errors().get(0).code() + ")");
            case PROCESSING -> "en proceso";
            case NOT_FOUND -> "no lo tiene registrado";
        };
    }
}
