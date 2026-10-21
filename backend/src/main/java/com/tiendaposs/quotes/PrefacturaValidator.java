package com.tiendaposs.quotes;

import com.tiendaposs.catalog.CatalogRepos.PresentationRepository;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Presentation;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.Money;
import com.tiendaposs.fiscal.FiscalCodes;
import com.tiendaposs.fiscal.FiscalProfile;
import com.tiendaposs.fiscal.FiscalRepos.FiscalProfileRepository;
import com.tiendaposs.fiscal.FiscalService;
import com.tiendaposs.fiscal.Municipalities;
import com.tiendaposs.fiscal.Nit;
import com.tiendaposs.pos.SaleCalculator;
import com.tiendaposs.quotes.QuoteRepos.QuoteRepository;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.settings.StoreSettings;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Revisión de una prefactura antes de facturar. Cada hallazgo es:
 * <ul>
 *   <li>ERROR: con eso la factura saldría mal o sería rechazada; no deja aprobar.</li>
 *   <li>WARNING: puede estar bien, pero alguien debe darlo por bueno (queda guardado quién y cuándo).</li>
 *   <li>INFO: solo para saber.</li>
 * </ul>
 * {@code fix} dice dónde se corrige: CLIENTE, LINEAS, FACTURACION o PRODUCTOS.
 */
@Component
public class PrefacturaValidator {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public record Issue(String severity, String code, String message, String fix) {
    }

    public record Result(boolean ok, int errors, int warnings, List<Issue> issues, BigDecimal recomputedTotal) {
    }

    private final ProductRepository products;
    private final PresentationRepository presentations;
    private final FiscalProfileRepository profiles;
    private final FiscalService fiscal;
    private final SettingsService settings;
    private final QuoteRepository quotes;
    private final QuoteService quoteService;

    public PrefacturaValidator(ProductRepository products, PresentationRepository presentations, FiscalProfileRepository profiles,
                               FiscalService fiscal, SettingsService settings, QuoteRepository quotes,
                               @Lazy QuoteService quoteService) {
        this.products = products;
        this.presentations = presentations;
        this.profiles = profiles;
        this.fiscal = fiscal;
        this.settings = settings;
        this.quotes = quotes;
        this.quoteService = quoteService;
    }

    public Result validate(Quote q, List<QuoteLine> ls) {
        List<Issue> out = new ArrayList<>();
        StoreSettings st = settings.forTenant(q.tenantId);
        FiscalProfile fp = profiles.findById(q.tenantId).orElse(null);
        boolean invoicing = fp != null && fp.invoicingEnabled;

        issuer(out, fp, invoicing);
        customer(out, q);
        lines(out, q, ls, st, invoicing);
        BigDecimal recomputed = totals(out, q, ls, st);
        others(out, q);

        int errors = (int) out.stream().filter(i -> "ERROR".equals(i.severity())).count();
        int warnings = (int) out.stream().filter(i -> "WARNING".equals(i.severity())).count();
        out.sort(Comparator.comparingInt(i -> switch (i.severity()) {
            case "ERROR" -> 0;
            case "WARNING" -> 1;
            default -> 2;
        }));
        return new Result(errors == 0, errors, warnings, out, recomputed);
    }

    // ------------------------------------------------------------------ Emisor (la tienda)

    private void issuer(List<Issue> out, FiscalProfile fp, boolean invoicing) {
        if (!invoicing) {
            out.add(new Issue("WARNING", "SIN_FACTURACION",
                    "Tu tienda aún no emite factura electrónica: al cobrar se entrega comprobante de venta, no factura.", "FACTURACION"));
            return;
        }
        List<String> missing = fiscal.missingProfileData(fp);
        if (!missing.isEmpty()) {
            out.add(new Issue("ERROR", "EMISOR_INCOMPLETO", "Faltan datos fiscales de tu negocio: " + String.join(", ", missing) + ".", "FACTURACION"));
        }
        var resolution = fiscal.readiness().steps().stream().filter(s -> "RESOLUCION".equals(s.key())).findFirst();
        if (resolution.isPresent() && !"OK".equals(resolution.get().status())) {
            out.add(new Issue("ERROR", "SIN_RESOLUCION", "Resolución de numeración: " + resolution.get().detail(), "FACTURACION"));
        } else if (resolution.isPresent() && resolution.get().detail().contains("Pide")) {
            out.add(new Issue("WARNING", "RESOLUCION_POR_TERMINAR", resolution.get().detail(), "FACTURACION"));
        }
    }

    // ------------------------------------------------------------------ Cliente (adquiriente)

    private void customer(List<Issue> out, Quote q) {
        if (q.customerName == null) out.add(new Issue("ERROR", "CLIENTE_NOMBRE", "Falta el nombre o la razón social del cliente.", "CLIENTE"));
        if (q.customerDocType == null || !FiscalCodes.ID_TYPES.containsKey(q.customerDocType)) {
            out.add(new Issue("ERROR", "CLIENTE_TIPO_DOC", "Elige el tipo de documento del cliente (cédula, NIT…).", "CLIENTE"));
        }
        String doc = q.customerDoc;
        if (doc == null) {
            out.add(new Issue("ERROR", "CLIENTE_DOC", "Falta el número de documento del cliente.", "CLIENTE"));
        } else if (Set.of("CC", "NIT", "TI", "RC").contains(q.customerDocType) && !doc.matches("[0-9]+")) {
            out.add(new Issue("ERROR", "CLIENTE_DOC_FORMATO", "El número de documento solo lleva números (sin puntos, guiones ni el DV).", "CLIENTE"));
        } else if ("NIT".equals(q.customerDocType)) {
            Character dv = Nit.dv(doc);
            if (q.customerDv == null) {
                out.add(new Issue("ERROR", "CLIENTE_DV", "Falta el dígito de verificación del NIT" + (dv == null ? "." : " (debería ser " + dv + ")."), "CLIENTE"));
            } else if (!Nit.matches(doc, q.customerDv)) {
                out.add(new Issue("ERROR", "CLIENTE_DV", "El dígito de verificación " + q.customerDv + " no corresponde al NIT " + doc
                        + (dv == null ? "." : ": debería ser " + dv + ". Revisa el número o el DV con el RUT del cliente."), "CLIENTE"));
            }
            if (doc.length() < 8 || doc.length() > 10) {
                out.add(new Issue("WARNING", "CLIENTE_NIT_LARGO", "Un NIT suele tener 9 dígitos (sin el DV); este tiene " + doc.length() + ".", "CLIENTE"));
            }
        } else if ("CC".equals(q.customerDocType) && (doc.length() < 5 || doc.length() > 10)) {
            out.add(new Issue("WARNING", "CLIENTE_CC_LARGO", "Una cédula tiene entre 5 y 10 dígitos; esta tiene " + doc.length() + ".", "CLIENTE"));
        }
        if (q.customerPersonType == null) {
            out.add(new Issue("ERROR", "CLIENTE_PERSONA", "Indica si el cliente es persona natural o jurídica.", "CLIENTE"));
        } else if ("JURIDICA".equals(q.customerPersonType) && !"NIT".equals(q.customerDocType) && q.customerDocType != null) {
            out.add(new Issue("WARNING", "CLIENTE_PERSONA_DOC", "Una persona jurídica normalmente se identifica con NIT.", "CLIENTE"));
        }
        if (q.customerEmail == null) {
            out.add(new Issue("ERROR", "CLIENTE_CORREO", "Falta el correo donde el cliente recibe la factura electrónica.", "CLIENTE"));
        } else if (!EMAIL.matcher(q.customerEmail).matches()) {
            out.add(new Issue("ERROR", "CLIENTE_CORREO", "El correo \"" + q.customerEmail + "\" no es válido.", "CLIENTE"));
        }
        if (q.customerMunicipality == null) {
            out.add(new Issue("WARNING", "CLIENTE_MUNICIPIO", "Falta el municipio del cliente (algunos proveedores de facturación lo exigen).", "CLIENTE"));
        } else if (!Municipalities.exists(q.customerMunicipality)) {
            out.add(new Issue("ERROR", "CLIENTE_MUNICIPIO", "El código de municipio " + q.customerMunicipality + " no existe en el DANE.", "CLIENTE"));
        }
        if (q.customerAddress == null) {
            out.add(new Issue("INFO", "CLIENTE_DIRECCION", "Sin dirección del cliente (opcional).", "CLIENTE"));
        }
        if ("CON_ERROR".equals(q.clientCheck)) {
            out.add(new Issue("ERROR", "CLIENTE_REPORTO", "El cliente reportó un error en sus datos"
                    + (q.clientCheckNote == null ? "." : ": \"" + q.clientCheckNote + "\".") + " Corrige y guarda la prefactura.", "CLIENTE"));
        } else if ("CONFIRMADO".equals(q.clientCheck)) {
            out.add(new Issue("INFO", "CLIENTE_CONFIRMO", "El cliente confirmó que sus datos están correctos.", null));
        } else {
            out.add(new Issue("INFO", "CLIENTE_SIN_CONFIRMAR", "Puedes enviarle el enlace al cliente para que confirme sus datos antes de facturar.", null));
        }
    }

    // ------------------------------------------------------------------ Productos y precios

    private void lines(List<Issue> out, Quote q, List<QuoteLine> ls, StoreSettings st, boolean invoicing) {
        if (ls.isEmpty()) {
            out.add(new Issue("ERROR", "SIN_LINEAS", "La prefactura no tiene productos ni servicios.", "LINEAS"));
            return;
        }
        boolean seeCost = CurrentUser.getOrNull() != null && CurrentUser.get().can(Permission.COSTOS_VER);
        Map<Long, BigDecimal> needByProduct = new LinkedHashMap<>();
        Map<Long, Product> byId = new HashMap<>();
        for (QuoteLine l : ls) {
            String name = "\"" + l.description + "\"";
            if (l.quantity.signum() <= 0) out.add(new Issue("ERROR", "LINEA_CANTIDAD", name + ": la cantidad debe ser mayor que cero.", "LINEAS"));
            if (l.unitPrice.signum() <= 0) out.add(new Issue("ERROR", "LINEA_PRECIO", name + ": el precio está en cero.", "LINEAS"));
            if (l.presentationId == null) {
                if (l.taxRate.signum() == 0) {
                    out.add(new Issue("WARNING", "LIBRE_SIN_IVA", name + " es una línea libre sin IVA: confirma que ese servicio o producto está excluido o exento.", "LINEAS"));
                }
                continue;
            }
            Presentation pr = presentations.findById(l.presentationId).orElse(null);
            Product p = l.productId == null ? null : products.findById(l.productId).orElse(null);
            if (p == null || pr == null) {
                out.add(new Issue("ERROR", "LINEA_NO_EXISTE", name + " ya no existe en el catálogo. Quítalo o cámbialo.", "LINEAS"));
                continue;
            }
            if (!"ACTIVE".equals(p.status) || !pr.active) {
                out.add(new Issue("ERROR", "LINEA_INACTIVA", name + " está descontinuado: actívalo o quítalo de la prefactura.", "LINEAS"));
            }
            BigDecimal rate = p.taxRate == null ? BigDecimal.ZERO : p.taxRate;
            if (rate.compareTo(l.taxRate) != 0) {
                out.add(new Issue("WARNING", "LINEA_IVA_CAMBIO", name + ": el producto ahora tiene " + p.taxName + " " + pct(rate)
                        + " y el documento se hizo con " + pct(l.taxRate) + ". Al cobrar se usa el vigente; guarda de nuevo para ver el total real.", "LINEAS"));
            }
            if (rate.signum() == 0 && p.taxClass == null) {
                out.add(new Issue(invoicing ? "ERROR" : "WARNING", "LINEA_SIN_CLASIFICAR", name
                        + " tiene IVA 0 % pero no está clasificado como exento o excluido.", "PRODUCTOS"));
            }
            if (pr.price.compareTo(l.unitPrice) != 0) {
                out.add(new Issue("WARNING", "LINEA_PRECIO_CAMBIO", name + ": hoy cuesta " + Money.fmt(pr.price) + " y se pactó en "
                        + Money.fmt(l.unitPrice) + ". Se cobra lo pactado.", "LINEAS"));
            }
            BigDecimal effective = l.unitPrice.multiply(BigDecimal.valueOf(100).subtract(l.discountPct)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal unitCost = p.cost == null ? BigDecimal.ZERO : p.cost.multiply(l.factor);
            if (unitCost.signum() > 0 && effective.compareTo(unitCost) < 0) {
                out.add(new Issue("WARNING", "LINEA_BAJO_COSTO", name + " se vende por debajo del costo"
                        + (seeCost ? " (" + Money.fmt(effective) + " contra un costo de " + Money.fmt(unitCost.setScale(0, RoundingMode.HALF_UP)) + ")." : "."), "LINEAS"));
            }
            if (p.trackSerials) {
                out.add(new Issue("WARNING", "LINEA_SERIAL", name + " lleva serial o IMEI: se cobra desde la caja para elegir cuál se entrega.", "LINEAS"));
            }
            if (p.trackStock && !"COMBO".equals(p.productType)) {
                needByProduct.merge(p.id, l.quantity.multiply(l.factor), BigDecimal::add);
                byId.put(p.id, p);
            }
        }
        for (var e : needByProduct.entrySet()) {
            Product p = byId.get(e.getKey());
            BigDecimal available = p.stock.subtract(p.reserved == null ? BigDecimal.ZERO : p.reserved);
            if (available.compareTo(e.getValue()) < 0) {
                out.add(new Issue(st.allowNegativeStock ? "WARNING" : "ERROR", "STOCK", "\"" + p.name + "\": se necesitan "
                        + qty(e.getValue()) + " y hay " + qty(available.max(BigDecimal.ZERO)) + " disponibles.", "LINEAS"));
            }
        }
    }

    // ------------------------------------------------------------------ Totales

    /** Recalcula con los ajustes de hoy; si no da lo mismo que lo guardado, alguien debe guardar de nuevo y revisar. */
    private BigDecimal totals(List<Issue> out, Quote q, List<QuoteLine> ls, StoreSettings st) {
        if (ls.isEmpty()) return BigDecimal.ZERO;
        Quote tmp = new Quote();
        tmp.tenantId = q.tenantId;
        tmp.saleDiscountAmount = q.saleDiscountAmount;
        List<QuoteLine> copy = ls.stream().map(l -> {
            QuoteLine c = new QuoteLine();
            c.unitPrice = l.unitPrice;
            c.quantity = l.quantity;
            c.factor = l.factor;
            c.discountPct = l.discountPct;
            c.taxRate = l.taxRate;
            c.fixedTax = l.fixedTax;
            return c;
        }).toList();
        try {
            quoteService.compute(tmp, copy, st);
        } catch (RuntimeException e) {
            out.add(new Issue("ERROR", "TOTALES", "No se pudieron calcular los totales: " + e.getMessage(), "LINEAS"));
            return q.total;
        }
        if (tmp.total.compareTo(q.total) != 0 || tmp.taxTotal.compareTo(q.taxTotal) != 0) {
            out.add(new Issue("ERROR", "TOTALES", "Los totales cambiaron al recalcular (guardado " + Money.fmt(q.total) + ", ahora "
                    + Money.fmt(tmp.total) + "): cambió la configuración de impuestos. Abre la prefactura y guárdala de nuevo.", "LINEAS"));
        }
        if (q.total.signum() <= 0) out.add(new Issue("ERROR", "TOTAL_CERO", "El total de la prefactura es cero.", "LINEAS"));
        return tmp.total;
    }

    // ------------------------------------------------------------------ Otros

    private void others(List<Issue> out, Quote q) {
        if (q.sourceId != null) {
            quotes.findById(q.sourceId).ifPresent(src -> {
                if (src.validUntil != null && src.validUntil.isBefore(quoteService.today(q.tenantId))) {
                    out.add(new Issue("WARNING", "COTIZACION_VENCIDA", "La cotización " + src.code() + " de donde salió venció el "
                            + src.validUntil + ": confirma que el cliente acepta estos precios.", "LINEAS"));
                }
            });
        }
        if (q.customerDoc != null && q.id != null) {
            var dups = quotes.sameCustomerAndTotal(q.tenantId, q.id, q.customerDoc, q.total, Instant.now().minus(30, ChronoUnit.DAYS));
            if (!dups.isEmpty()) {
                out.add(new Issue("WARNING", "POSIBLE_DUPLICADO", "Ya hay otra prefactura para este cliente por el mismo valor ("
                        + dups.get(0).code() + "). Revisa que no vayas a facturar dos veces.", null));
            }
        }
    }

    private static String pct(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString() + " %";
    }

    private static String qty(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
