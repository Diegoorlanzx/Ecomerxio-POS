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
            SOPORTE = "DOCUMENTO_SOPORTE";

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
    private final Counters counters;
    private final EInvoiceProvider provider;
    private final AuditService audit;
    private final EntityManager em;
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
                           SupplierRepository suppliers, Counters counters, EInvoiceProvider provider, AuditService audit,
                           EntityManager em, TransactionTemplate tx, @Value("${app.scheduler.enabled:true}") boolean async) {
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
        this.counters = counters;
        this.provider = provider;
        this.audit = audit;
        this.em = em;
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

    /**
     * Venta cobrada (caja, cuenta abierta, pedido en línea o venta sincronizada sin internet). Se llama dentro de la
     * transacción de la venta. Si el cliente pidió factura y le faltan datos, la venta en línea se rechaza para que el
     * cajero los complete; la venta hecha sin internet nunca se rechaza: su factura queda BLOQUEADA hasta completarlos.
     */
    public void onSaleCompleted(Sale s) {
        FiscalProfile p = profiles.findById(s.tenantId).orElse(null);
        if (p == null || !p.invoicingEnabled) return;
        if (docs.findSaleDocument(s.id).isPresent()) return;
        String type = POS;
        Customer c = s.customerId == null ? null : customers.findById(s.customerId).orElse(null);
        if (s.invoiceRequested) {
            List<String> missing = c == null ? List.of("cliente identificado") : com.tiendaposs.pos.PosCrudService.fiscalMissingOf(c);
            if (!missing.isEmpty() && !s.offline) {
                throw ApiException.field("customer", "Para la factura electrónica falta: " + String.join(", ", missing)
                        + ". Complétalo o cobra sin factura a nombre del cliente.");
            }
            type = FACTURA;
        }
        ElectronicDocument d = newDocument(s.tenantId, p, type, "T" + s.tenantId + "-S" + s.id + "-" + type);
        d.saleId = s.id;
        d.issueDate = s.completedAt == null ? Instant.now() : s.completedAt;
        d.total = s.total;
        d.taxTotal = s.taxTotal;
        if (FACTURA.equals(type) && c != null) {
            d.customerName = c.name;
            d.customerIdType = c.docType;
            d.customerIdNumber = c.docNumber;
        } else {
            d.customerName = "Consumidor final";
            d.customerIdType = "CC";
            d.customerIdNumber = FiscalCodes.FINAL_CONSUMER_ID;
        }
        assignNumber(d, p);
        saveNew(d, "Creado por la venta #" + s.number + (s.offline ? " (hecha sin internet)" : ""));
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
        String ref = "T" + original.tenantId + "-NC-" + (returnId == null ? "V" + original.saleId : "R" + returnId);
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
        n.reason = reason.length() > 300 ? reason.substring(0, 300) : reason;
        // Las notas llevan su propio consecutivo, sin resolución.
        n.prefix = "NC";
        n.number = counters.next(original.tenantId, "NOTA_CREDITO");
        n.fullNumber = n.prefix + n.number;
        saveNew(n, "Nota crédito sobre " + original.fullNumber + ". " + n.reason);
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

    public void setEnabled(Long tenantId, boolean on) {
        FiscalProfile p = profiles.findById(tenantId).orElseThrow(() -> ApiException.badRequest("Completa primero los datos fiscales"));
        if (on) {
            if (!fiscal.readiness().ready()) throw ApiException.badRequest("Completa todos los pasos del asistente antes de activar la facturación.");
            if ("PRODUCCION".equals(p.environment) && provider.simulated()) {
                throw ApiException.badRequest("Ecomerxio POS todavía no tiene un proveedor tecnológico real conectado: "
                        + "por ahora la facturación solo se activa en el ambiente de pruebas.");
            }
        }
        p.invoicingEnabled = on;
        profiles.save(p);
        audit.sensitive(on ? "FACTURACION_ACTIVADA" : "FACTURACION_DESACTIVADA", "TENANT", tenantId,
                (on ? "Activó" : "Desactivó") + " la facturación electrónica (" + p.environment.toLowerCase() + ")");
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
        if (NOTA_CREDITO.equals(d.documentType)) {
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
                              Long supplierId) {
    }

    public record EventDto(String type, String statusAfter, String code, String message, String userName, Instant createdAt) {
    }

    public record DocumentDetail(DocumentDto document, List<EventDto> events, List<DocumentDto> notes) {
    }

    public record Summary(boolean enabled, String environment, String provider, boolean simulated, Map<String, Long> byStatus) {
    }

    public DocumentDto dto(ElectronicDocument d) {
        Long saleNumber = d.saleId == null ? null : sales.findById(d.saleId).map(s -> s.number).orElse(null);
        String original = d.originalId == null ? null : docs.findById(d.originalId).map(o -> o.fullNumber).orElse(null);
        String label = NOTA_CREDITO.equals(d.documentType) ? "Nota crédito" : FiscalCodes.DOCUMENT_TYPES.get(d.documentType);
        GoodsReceipt rec = d.receiptId == null ? null : receipts.findById(d.receiptId).orElse(null);
        return new DocumentDto(d.id, d.documentType, label, d.fullNumber, d.status, d.environment, d.provider,
                "SIMULADO".equals(d.provider), d.test, d.saleId, saleNumber, d.returnId, original, d.customerName, d.customerIdNumber,
                d.total, d.taxTotal, d.issueDate, d.cufe, d.qrData, d.attempts, d.nextAttemptAt, d.lastErrorCode, d.lastErrorMessage,
                d.reason, d.acceptedAt, d.createdAt,
                Set.of(RECHAZADO, BLOQUEADO, DESCONOCIDO).contains(d.status) ? ErrorCatalog.help(d.lastErrorCode, d.status) : null,
                d.receiptId, rec == null ? null : rec.number, rec == null ? null : rec.supplierId);
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
        return new Summary(p != null && p.invoicingEnabled, p == null ? "PRUEBAS" : p.environment, provider.code(), provider.simulated(), by);
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

        if (SOPORTE.equals(d.documentType)) return supportPayload(d, issuer, problems);

        Sale sale = d.saleId == null ? null : sales.findById(d.saleId).orElse(null);
        Party buyer = finalConsumer();
        boolean finalConsumer = true;
        if (FACTURA.equals(d.documentType) || (NOTA_CREDITO.equals(d.documentType) && !FiscalCodes.FINAL_CONSUMER_ID.equals(d.customerIdNumber))) {
            Customer c = sale == null || sale.customerId == null ? null : customers.findById(sale.customerId).orElse(null);
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
                d.issueDate, issuer, buyer, finalConsumer, lines, d.taxTotal, d.total, originalNumber, originalCufe, d.reason);
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
                d.issueDate, issuer, seller, false, lines, BigDecimal.ZERO, d.total, null, null, null);
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
