package com.tiendaposs.purchasing;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogRepos.PresentationRepository;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Presentation;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Money;
import com.tiendaposs.fiscal.FiscalProfile;
import com.tiendaposs.fiscal.FiscalRepos.FiscalProfileRepository;
import com.tiendaposs.purchasing.PurchaseService.ReceiveLine;
import com.tiendaposs.purchasing.PurchaseService.ReceiveRequest;
import com.tiendaposs.purchasing.PurchasingRepos.*;
import com.tiendaposs.purchasing.SupplierInvoiceParser.ParsedInvoice;
import com.tiendaposs.purchasing.SupplierInvoiceParser.ParsedLine;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Fase 13E: la factura electrónica del proveedor (XML o ZIP) se convierte en una recepción de mercancía.
 * <ol>
 *   <li>Vista previa: se lee el XML, se busca el proveedor por NIT y cada producto por lo que se recordó de facturas
 *       anteriores (código del proveedor), por código de barras o por SKU. Nada se guarda.</li>
 *   <li>Importar: con los productos confirmados se recibe la mercancía (con o sin orden de compra), la cuenta por pagar
 *       queda por el total de la factura con su vencimiento, y se recuerda cada producto para la próxima vez.</li>
 * </ol>
 * La misma factura no entra dos veces (por CUFE, o por proveedor y número). Se guarda la copia del XML.
 */
@Service
public class SupplierInvoiceService {

    private final SupplierInvoiceRepository invoices;
    private final SupplierRepository supplierRepo;
    private final SupplierService suppliers;
    private final SupplierProductRepository supplierProducts;
    private final ProductRepository products;
    private final PresentationRepository presentations;
    private final PurchaseOrderRepository orders;
    private final PurchaseOrderLineRepository orderLines;
    private final GoodsReceiptRepository receipts;
    private final PurchaseService purchases;
    private final FiscalProfileRepository profiles;
    private final AuditService audit;

    public SupplierInvoiceService(SupplierInvoiceRepository invoices, SupplierRepository supplierRepo, SupplierService suppliers,
                                  SupplierProductRepository supplierProducts, ProductRepository products,
                                  PresentationRepository presentations, PurchaseOrderRepository orders,
                                  PurchaseOrderLineRepository orderLines, GoodsReceiptRepository receipts, PurchaseService purchases,
                                  FiscalProfileRepository profiles, AuditService audit) {
        this.invoices = invoices;
        this.supplierRepo = supplierRepo;
        this.suppliers = suppliers;
        this.supplierProducts = supplierProducts;
        this.products = products;
        this.presentations = presentations;
        this.orders = orders;
        this.orderLines = orderLines;
        this.receipts = receipts;
        this.purchases = purchases;
        this.profiles = profiles;
        this.audit = audit;
    }

    // ---------------- DTOs ----------------

    public record FileRequest(@Size(max = 200) String fileName, @NotBlank(message = "sube el archivo") String contentBase64) {
    }

    /** matchedBy: MEMORIA (se recordó de otra factura), CODIGO_BARRAS o SKU. */
    public record LineMatch(Long productId, String productName, String sku, BigDecimal packFactor, String matchedBy) {
    }

    public record PreviewLine(int index, String code, String barcode, String description, BigDecimal quantity, String unitCode,
                              BigDecimal unitPrice, BigDecimal lineTotal, BigDecimal taxRate, BigDecimal tax, LineMatch match) {
    }

    public record SupplierMatch(Long id, String name, boolean invoicesElectronically) {
    }

    public record OpenOrder(Long id, long number, String status, LocalDate expectedDate, BigDecimal estimatedTotal) {
    }

    public record Preview(String fileName, String number, String cufe, LocalDate issueDate, LocalDate dueDate, String currency,
                          String supplierTaxId, String supplierDv, String supplierName, String supplierEmail, String supplierAddress,
                          String buyerTaxId, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal total, SupplierMatch supplier,
                          List<OpenOrder> openOrders, List<PreviewLine> lines, List<String> warnings,
                          /** true si el IVA de la compra se suma al costo (el negocio no es responsable de IVA). */ boolean costIncludesTax) {
    }

    public record ImportLine(@NotNull Integer index, Long productId,
                             @DecimalMin(value = "0.001", message = "debe ser mayor que cero") BigDecimal packFactor, Boolean skip) {
    }

    public record ImportRequest(@Size(max = 200) String fileName, @NotBlank(message = "sube el archivo") String contentBase64,
                                Long supplierId, Boolean createSupplier, Long orderId,
                                @NotNull @Size(min = 1, message = "confirma los productos") List<@Valid ImportLine> lines) {
    }

    public record InvoiceDto(Long id, Long supplierId, String supplierName, String invoiceNumber, String cufe, LocalDate issueDate,
                             LocalDate dueDate, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal total, Long receiptId,
                             Long receiptNumber, Long orderId, Long orderNumber, String fileName, String importedBy, Instant createdAt) {
    }

    // ---------------- Vista previa ----------------

    @Transactional(readOnly = true)
    public Preview preview(FileRequest r) {
        CurrentUser.require(Permission.COMPRAS_RECIBIR);
        Long t = TenantContext.require();
        ParsedInvoice inv = SupplierInvoiceParser.parse(decode(r.contentBase64()));
        Supplier s = findSupplier(t, inv.supplierTaxId());
        rejectDuplicate(t, inv, s);

        List<String> warnings = new ArrayList<>(warnings(t, inv));
        if (s == null) warnings.add("No hay un proveedor con el NIT " + inv.supplierTaxId() + ": elige uno o créalo con los datos de la factura.");
        else if (!s.invoicesElectronically) warnings.add(s.name + " está marcado como NO obligado a facturar, pero envió factura electrónica: "
                + "corrígelo en su ficha antes de importar.");

        Map<String, SupplierProduct> remembered = s == null ? Map.of() : rememberedOf(s.id);
        Map<Long, Product> byId = new HashMap<>();
        List<PreviewLine> lines = new ArrayList<>();
        for (ParsedLine l : inv.lines()) {
            LineMatch m = match(t, l, remembered, byId);
            lines.add(new PreviewLine(l.index(), l.code(), l.barcode(), l.description(), l.quantity(), l.unitCode(), l.unitPrice(),
                    l.lineTotal(), l.taxRate(), l.tax(), m));
        }
        List<OpenOrder> open = s == null ? List.of() : orders.findByTenantIdAndStatusIn(t, PurchaseService.OPEN).stream()
                .filter(o -> o.supplierId.equals(s.id)).sorted(Comparator.comparing((PurchaseOrder o) -> o.number).reversed())
                .map(o -> new OpenOrder(o.id, o.number, o.status, o.expectedDate, o.estimatedTotal)).toList();
        return new Preview(r.fileName(), inv.number(), inv.cufe(), inv.issueDate(), inv.dueDate(), inv.currency(), inv.supplierTaxId(),
                inv.supplierDv(), inv.supplierName(), inv.supplierEmail(), inv.supplierAddress(), inv.buyerTaxId(), inv.subtotal(),
                inv.taxTotal(), inv.total(), s == null ? null : new SupplierMatch(s.id, s.name, s.invoicesElectronically), open, lines,
                warnings, costIncludesTax(t));
    }

    // ---------------- Importar ----------------

    @Transactional
    public InvoiceDto importInvoice(ImportRequest r) {
        CurrentUser.require(Permission.COMPRAS_RECIBIR);
        Long t = TenantContext.require();
        ParsedInvoice inv = SupplierInvoiceParser.parse(decode(r.contentBase64()));
        Supplier s = resolveSupplier(t, inv, r);
        rejectDuplicate(t, inv, s);
        boolean withTax = costIncludesTax(t);

        Map<Integer, ImportLine> chosen = new HashMap<>();
        for (ImportLine l : r.lines()) chosen.put(l.index(), l);
        PurchaseOrder order = r.orderId() == null ? null : purchases.find(r.orderId());
        if (order != null && !order.supplierId.equals(s.id)) throw ApiException.field("orderId", "la orden #" + order.number + " es de otro proveedor");
        List<PurchaseOrderLine> ols = order == null ? List.of() : orderLines.findByOrderIdOrderByIdAsc(order.id);

        List<ReceiveLine> receive = new ArrayList<>();
        for (ParsedLine pl : inv.lines()) {
            ImportLine c = chosen.get(pl.index());
            String name = "«" + (pl.description() == null ? "línea " + (pl.index() + 1) : pl.description()) + "»";
            if (c != null && Boolean.TRUE.equals(c.skip())) continue;
            if (c == null || c.productId() == null) throw ApiException.field("lines", "elige el producto de " + name + " o márcala para no recibirla");
            if (pl.quantity().signum() <= 0) throw ApiException.field("lines", name + " no tiene cantidad: márcala para no recibirla");
            Product p = products.findByIdAndTenantId(c.productId(), t).orElseThrow(() -> ApiException.field("lines", "un producto no existe"));
            if (p.trackSerials) {
                throw ApiException.field("lines", p.name + " lleva seriales: recíbelo desde la orden de compra para registrar cada uno");
            }
            BigDecimal factor = c.packFactor() == null ? BigDecimal.ONE : c.packFactor();
            BigDecimal units = pl.quantity().multiply(factor);
            BigDecimal amount = withTax ? pl.lineTotal().add(pl.tax()) : pl.lineTotal();
            BigDecimal unitCost = amount.divide(units, 4, RoundingMode.HALF_UP);
            remember(s, p, pl, factor, amount.divide(pl.quantity(), 2, RoundingMode.HALF_UP));
            Long orderLineId = null;
            if (order != null) {
                orderLineId = ols.stream().filter(ol -> ol.productId.equals(p.id))
                        .filter(ol -> ol.unitsOrdered().subtract(ol.unitsReceived).subtract(ol.unitsRejected).signum() > 0)
                        .findFirst().or(() -> ols.stream().filter(ol -> ol.productId.equals(p.id)).findFirst())
                        .map(ol -> ol.id).orElseThrow(() -> ApiException.field("lines", p.name + " no está en la orden #"
                                + order.number + ": impórtala sin orden o agrégalo a la orden"));
            }
            receive.add(new ReceiveLine(orderLineId, p.id, units, BigDecimal.ZERO, null, unitCost, null, null, null));
        }
        if (receive.isEmpty()) throw ApiException.field("lines", "no hay ningún producto para recibir");

        String invoiceNumber = inv.number().length() > 60 ? inv.number().substring(0, 60) : inv.number();
        String notes = "Importada de la factura electrónica " + inv.number() + " (total con impuestos " + Money.fmt(inv.total()) + ")";
        ReceiveRequest req = new ReceiveRequest(receive, invoiceNumber, notes, s.id);
        GoodsReceipt rec = order != null ? purchases.receiveOrder(order, req) : purchases.receiveWithoutOrder(req);
        // Lo que se le debe al proveedor es el total de la factura (con IVA y cargos), con su vencimiento.
        if (inv.total().signum() > 0) rec.total = inv.total().setScale(2, RoundingMode.HALF_UP);
        if (inv.dueDate() != null) rec.dueDate = inv.dueDate();
        receipts.save(rec);

        SupplierInvoice si = new SupplierInvoice();
        si.tenantId = t;
        si.supplierId = s.id;
        si.receiptId = rec.id;
        si.orderId = order == null ? null : order.id;
        si.invoiceNumber = invoiceNumber;
        si.cufe = inv.cufe();
        si.issueDate = inv.issueDate();
        si.dueDate = inv.dueDate();
        si.supplierTaxId = inv.supplierTaxId();
        si.supplierName = inv.supplierName();
        si.buyerTaxId = inv.buyerTaxId();
        si.subtotal = inv.subtotal();
        si.taxTotal = inv.taxTotal();
        si.total = inv.total();
        si.fileName = r.fileName();
        si.xml = inv.invoiceXml();
        si.importedBy = CurrentUser.get().fullName();
        invoices.save(si);
        audit.log("FACTURA_PROVEEDOR_IMPORTADA", "RECEIPT", rec.id, "Importó la factura " + inv.number() + " de " + s.name + " por "
                + Money.fmt(inv.total()) + " (recepción #" + rec.number + (order == null ? "" : ", orden #" + order.number) + ")");
        return dto(si, Map.of(s.id, s.name));
    }

    @Transactional(readOnly = true)
    public List<InvoiceDto> list() {
        Long t = TenantContext.require();
        List<SupplierInvoiceRow> rows = invoices.findTop200ByTenantIdOrderByCreatedAtDescIdDesc(t);
        Map<Long, String> names = supplierRepo.findAllById(rows.stream().map(SupplierInvoiceRow::getSupplierId).distinct().toList())
                .stream().collect(Collectors.toMap(x -> x.id, x -> x.name));
        Map<Long, Long> recNumbers = receipts.findAllById(rows.stream().map(SupplierInvoiceRow::getReceiptId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(x -> x.id, x -> x.number));
        Map<Long, Long> orderNumbers = orders.findAllById(rows.stream().map(SupplierInvoiceRow::getOrderId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(x -> x.id, x -> x.number));
        return rows.stream().map(x -> new InvoiceDto(x.getId(), x.getSupplierId(), names.get(x.getSupplierId()), x.getInvoiceNumber(),
                x.getCufe(), x.getIssueDate(), x.getDueDate(), x.getSubtotal(), x.getTaxTotal(), x.getTotal(), x.getReceiptId(),
                recNumbers.get(x.getReceiptId()), x.getOrderId(), orderNumbers.get(x.getOrderId()), x.getFileName(), x.getImportedBy(),
                x.getCreatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public SupplierInvoice find(Long id) {
        return invoices.findByIdAndTenantId(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Factura no encontrada"));
    }

    // ---------------- Auxiliares ----------------

    private Supplier resolveSupplier(Long t, ParsedInvoice inv, ImportRequest r) {
        Supplier s;
        if (r.supplierId() != null) {
            s = suppliers.find(r.supplierId());
        } else if (Boolean.TRUE.equals(r.createSupplier())) {
            String name = inv.supplierName() == null ? "Proveedor " + inv.supplierTaxId() : inv.supplierName();
            name = name.length() > 120 ? name.substring(0, 120) : name;
            var dto = suppliers.save(null, new SupplierService.SupplierRequest(name, inv.supplierName(), inv.supplierTaxId(), null, null, null,
                    inv.supplierEmail(), cut(inv.supplierAddress(), 200), null, null, 0, null, null, null,
                    "Creado al importar la factura " + inv.number(), true, "NIT", validDv(inv), inv.supplierPersonType(), null, true));
            s = suppliers.find(dto.id());
        } else {
            throw ApiException.field("supplierId", "elige el proveedor o créalo con los datos de la factura");
        }
        if (!s.invoicesElectronically) {
            throw ApiException.field("supplierId", s.name + " está marcado como NO obligado a facturar, pero esta es una factura electrónica: "
                    + "corrige su ficha (si factura, no lleva documento soporte) y vuelve a importar.");
        }
        String own = digits(s.taxId);
        if (own != null && inv.supplierTaxId() != null && !own.equals(inv.supplierTaxId())) {
            throw ApiException.field("supplierId", "la factura es del NIT " + inv.supplierTaxId() + " y " + s.name + " tiene el NIT " + s.taxId);
        }
        if (own == null && inv.supplierTaxId() != null) {
            // Se completa la ficha con lo que dice la factura.
            s.taxId = inv.supplierTaxId();
            if (s.dv == null) s.dv = validDv(inv);
            if (s.legalName == null) s.legalName = inv.supplierName();
            supplierRepo.save(s);
        }
        return s;
    }

    private Supplier findSupplier(Long t, String taxId) {
        if (taxId == null) return null;
        return supplierRepo.findByTenantIdOrderByNameAsc(t).stream().filter(x -> taxId.equals(digits(x.taxId)))
                .min(Comparator.comparing((Supplier x) -> !x.active)).orElse(null);
    }

    private void rejectDuplicate(Long t, ParsedInvoice inv, Supplier s) {
        Optional<SupplierInvoice> dup = inv.cufe() == null ? Optional.empty() : invoices.findByTenantIdAndCufe(t, inv.cufe());
        if (dup.isEmpty() && s != null) dup = invoices.findByTenantIdAndSupplierIdAndInvoiceNumber(t, s.id, inv.number());
        dup.ifPresent(d -> {
            Long n = d.receiptId == null ? null : receipts.findById(d.receiptId).map(x -> x.number).orElse(null);
            throw ApiException.badRequest("La factura " + inv.number() + " ya se importó" + (n == null ? "" : " (recepción #" + n + ")")
                    + ": no se recibe dos veces.");
        });
    }

    private List<String> warnings(Long t, ParsedInvoice inv) {
        List<String> w = new ArrayList<>();
        FiscalProfile p = profiles.findById(t).orElse(null);
        String own = p == null ? null : digits(p.idNumber);
        if (own != null && inv.buyerTaxId() != null && !own.equals(inv.buyerTaxId())) {
            w.add("La factura está a nombre del NIT " + inv.buyerTaxId() + ", no del negocio (" + p.idNumber + "). Revisa que sea tuya.");
        }
        if (inv.currency() != null && !"COP".equalsIgnoreCase(inv.currency())) {
            w.add("La factura está en " + inv.currency() + ": los valores se toman tal cual, como si fueran pesos.");
        }
        BigDecimal lines = inv.lines().stream().map(l -> l.lineTotal().add(l.tax())).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (inv.total().signum() > 0 && lines.subtract(inv.total()).abs().compareTo(BigDecimal.ONE) > 0) {
            w.add("Los productos con impuestos suman " + Money.fmt(lines) + " y el total a pagar es " + Money.fmt(inv.total())
                    + " (descuentos, cargos o retenciones de la factura). La cuenta por pagar queda por el total a pagar.");
        }
        return w;
    }

    /** Si el negocio no es responsable de IVA, el IVA que paga es parte del costo; si lo es, lo descuenta y no va al costo. */
    private boolean costIncludesTax(Long t) {
        return profiles.findById(t).map(p -> !Boolean.TRUE.equals(p.ivaResponsible)).orElse(true);
    }

    /** Lo recordado de facturas anteriores: código (o descripción) del proveedor → producto y empaque. */
    private Map<String, SupplierProduct> rememberedOf(Long supplierId) {
        Map<String, SupplierProduct> out = new HashMap<>();
        for (SupplierProduct sp : supplierProducts.findBySupplierId(supplierId)) {
            if (sp.supplierSku != null) out.put(sp.supplierSku.trim().toLowerCase(), sp);
        }
        return out;
    }

    private LineMatch match(Long t, ParsedLine l, Map<String, SupplierProduct> remembered, Map<Long, Product> cache) {
        Function<Long, Product> product = id -> cache.computeIfAbsent(id, k -> products.findByIdAndTenantId(k, t).orElse(null));
        for (String k : new String[]{l.key(), l.code(), l.barcode(), l.description()}) {
            if (k == null) continue;
            SupplierProduct sp = remembered.get(k.trim().toLowerCase());
            Product p = sp == null ? null : product.apply(sp.productId);
            if (p != null) return new LineMatch(p.id, p.name, p.sku, sp.packFactor, "MEMORIA");
        }
        for (String code : new String[]{l.barcode(), l.code()}) {
            if (code == null) continue;
            Optional<Presentation> pr = presentations.findByBarcode(t, code);
            if (pr.isPresent()) {
                Product p = product.apply(pr.get().productId);
                if (p != null) return new LineMatch(p.id, p.name, p.sku, pr.get().factor, "CODIGO_BARRAS");
            }
        }
        if (l.code() != null) {
            Product p = products.findBySku(t, l.code()).orElse(null);
            if (p != null) return new LineMatch(p.id, p.name, p.sku, BigDecimal.ONE, "SKU");
        }
        return null;
    }

    /** Guarda la relación para la próxima factura (y el empaque con que lo vende este proveedor). */
    private void remember(Supplier s, Product p, ParsedLine l, BigDecimal factor, BigDecimal costPerPack) {
        String key = l.key();
        SupplierProduct sp = supplierProducts.findBySupplierIdAndProductId(s.id, p.id).orElse(null);
        if (sp == null) {
            sp = new SupplierProduct();
            sp.tenantId = s.tenantId;
            sp.supplierId = s.id;
            sp.productId = p.id;
            sp.costPerPack = costPerPack;
            sp.isPrimary = supplierProducts.findByProductId(p.id).isEmpty();
        }
        if (sp.packFactor == null || sp.packFactor.compareTo(factor) != 0) {
            sp.packFactor = factor;
            sp.packName = factor.compareTo(BigDecimal.ONE) == 0 ? "Unidad" : "Empaque x" + factor.stripTrailingZeros().toPlainString();
        }
        if (key != null) sp.supplierSku = key;
        sp.updatedAt = Instant.now();
        supplierProducts.save(sp);
    }

    private InvoiceDto dto(SupplierInvoice x, Map<Long, String> names) {
        Long recNumber = x.receiptId == null ? null : receipts.findById(x.receiptId).map(r -> r.number).orElse(null);
        Long orderNumber = x.orderId == null ? null : orders.findById(x.orderId).map(o -> o.number).orElse(null);
        return new InvoiceDto(x.id, x.supplierId, names.get(x.supplierId), x.invoiceNumber, x.cufe, x.issueDate, x.dueDate, x.subtotal,
                x.taxTotal, x.total, x.receiptId, recNumber, x.orderId, orderNumber, x.fileName, x.importedBy, x.createdAt);
    }

    private static String validDv(ParsedInvoice inv) {
        String dv = inv.supplierDv();
        return dv != null && inv.supplierTaxId() != null && com.tiendaposs.fiscal.Nit.matches(inv.supplierTaxId(), dv) ? dv : null;
    }

    private static byte[] decode(String b64) {
        try {
            String clean = b64.contains(",") && b64.startsWith("data:") ? b64.substring(b64.indexOf(',') + 1) : b64;
            return Base64.getMimeDecoder().decode(clean);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("El archivo no llegó bien: vuelve a subirlo");
        }
    }

    /** Solo los dígitos del NIT, sin el DV si viene después del guion ("900123456-6" → "900123456"). */
    private static String digits(String s) {
        if (s == null) return null;
        int dash = s.lastIndexOf('-');
        if (dash > 0 && s.substring(dash + 1).trim().matches("\\d")) s = s.substring(0, dash);
        String d = s.replaceAll("\\D", "");
        return d.isEmpty() ? null : d;
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
