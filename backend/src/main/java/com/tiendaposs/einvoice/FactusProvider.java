package com.tiendaposs.einvoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Fase 16D: proveedor tecnológico Factus (API v2, developers.factus.com.co, leída el 22 oct 2026).
 * <ul>
 *   <li>Token OAuth "password" (client_id, client_secret, usuario, clave); se renueva con refresh_token o se pide otro.</li>
 *   <li>Factura y POS → /v2/bills/validate (Factus no tiene documento equivalente POS: sale como factura, a consumidor
 *       final si no hay cliente). Nota crédito → /v2/credit-notes/validate; débito → /v2/debit-notes/validate;
 *       documento soporte → /v2/support-documents/validate; nota de ajuste → /v2/adjustment-notes/validate.</li>
 *   <li>reference_code = nuestra referencia externa: Factus la trata como llave única (reenviar devuelve el mismo
 *       documento), así que la regla "consultar antes de reenviar" se cumple también para las notas.</li>
 *   <li>La numeración la pone Factus con su rango activo: el número que devuelve reemplaza el provisional.</li>
 * </ul>
 * Sin probar contra el sandbox (falta que el usuario ponga las credenciales). Lo marcado "a confirmar" en
 * docs/PLAN_FASE_16_FACTURACION_MIXTA.md se revisa en la primera prueba real.
 */
public class FactusProvider implements EInvoiceProvider {

    private static final Logger log = LoggerFactory.getLogger(FactusProvider.class);

    public record Config(String baseUrl, String clientId, String clientSecret, String username, String password) {
    }

    private final Config cfg;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private String accessToken;
    private String refreshToken;
    private Instant expiresAt = Instant.EPOCH;

    public FactusProvider(Config cfg) {
        this(cfg, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    FactusProvider(Config cfg, HttpClient http) {
        this.cfg = cfg;
        this.http = http;
    }

    @Override
    public String code() {
        return "FACTUS";
    }

    @Override
    public boolean simulated() {
        return false;
    }

    // =================================================================== Envío y consulta

    @Override
    public Result send(Payload d) throws ProviderUnavailableException {
        String path = switch (d.documentType()) {
            case EInvoiceService.NOTA_CREDITO -> "/v2/credit-notes/validate";
            case EInvoiceService.NOTA_DEBITO -> "/v2/debit-notes/validate";
            case EInvoiceService.SOPORTE -> "/v2/support-documents/validate";
            case EInvoiceService.NOTA_AJUSTE_SOPORTE -> "/v2/adjustment-notes/validate";
            default -> "/v2/bills/validate";
        };
        HttpResponse<String> res = call("POST", path, body(d).toString(), true);
        return interpret(res);
    }

    /** Claves de la nómina electrónica que Factus recibe como un solo objeto (las demás son listas). */
    private static final java.util.Set<String> SINGLE_ACCRUALS = java.util.Set.of("suel", "prim", "dota", "tele", "reti", "inde", "rein", "apoy");
    private static final java.util.Set<String> SINGLE_DEDUCTIONS = java.util.Set.of("salu", "pens", "pevo", "rete", "afco", "coop", "emba",
            "plan", "educ", "rein", "deud");

    /**
     * Fase 16F: nómina individual → POST /v2/payrolls; su nota de ajuste (eliminación) → POST /v2/adjustment-payrolls.
     * A confirmar con Factus: la documentación también menciona /v2/payroll/validate.
     */
    @Override
    public Result sendPayroll(PayrollPayload p) throws ProviderUnavailableException {
        ObjectNode b = json.createObjectNode();
        b.put("reference_code", p.externalRef());
        if (EInvoiceService.NOMINA_AJUSTE.equals(p.documentType())) {
            b.put("payroll_number", p.originalNumber());
            return interpret(call("POST", "/v2/adjustment-payrolls", b.toString(), true));
        }
        ObjectNode period = b.putObject("settlement_period");
        period.put("month", p.month());
        period.put("year", p.year());
        period.put("payroll_period_code", "5");
        ObjectNode pay = b.putObject("payment");
        pay.put("payment_method_code", p.paymentMethodCode() == null ? "10" : p.paymentMethodCode());
        if (p.bankName() != null) pay.put("bank_name", p.bankName());
        if (p.accountType() != null) pay.put("account_type", p.accountType());
        if (p.accountNumber() != null) pay.put("account_number", p.accountNumber());
        pay.put("payment_date", (p.paymentDate() == null ? java.time.LocalDate.now() : p.paymentDate()).toString());
        ObjectNode w = b.putObject("worker");
        w.put("identification_document_code", p.workerDocCode());
        w.put("identification_number", p.workerDoc());
        w.put("first_name", p.firstName());
        if (p.otherNames() != null) w.put("other_names", p.otherNames());
        w.put("first_surname", p.firstSurname());
        if (p.secondSurname() != null) w.put("second_surname", p.secondSurname());
        w.put("address", p.address());
        w.put("country_code", "CO");
        if (p.municipalityCode() != null) w.put("municipality_code", p.municipalityCode());
        w.put("has_integral_salary", p.integral());
        w.put("has_high_risk", p.highRisk());
        w.put("worker_type_code", p.workerType());
        w.put("worker_subtype", p.workerSubtype());
        w.put("contract_type", p.contractType());
        w.put("salary", money(p.salary()));
        w.put("entry_date", p.entryDate().toString());
        if (p.retirementDate() != null) w.put("retirement_date", p.retirementDate().toString());
        w.put("days_worked", String.valueOf(p.daysWorked()));
        b.set("accruals", group(p.earnings(), SINGLE_ACCRUALS, "accrual_type_code"));
        b.set("deductions", group(p.deductions(), SINGLE_DEDUCTIONS, "deduction_type_code"));
        Result r = interpret(call("POST", "/v2/payrolls", b.toString(), true));
        // La nómina trae CUNE en vez de CUFE y el QR en data.qr: interpret ya los busca.
        return r;
    }

    /** Agrupa las líneas por su clave: objetos únicos (sumados) o listas, con los campos que pide Factus. */
    private ObjectNode group(List<PayrollItem> items, java.util.Set<String> singles, String typeField) {
        ObjectNode out = json.createObjectNode();
        for (PayrollItem it : items) {
            if (it.amount() == null) continue;
            ObjectNode n;
            if (singles.contains(it.key())) {
                n = out.has(it.key()) ? (ObjectNode) out.get(it.key()) : out.putObject(it.key());
                BigDecimal prev = n.has("amount") ? new BigDecimal(n.get("amount").asText()) : BigDecimal.ZERO;
                n.put("amount", money(prev.add(it.amount())));
            } else {
                ArrayNode arr = out.has(it.key()) ? (ArrayNode) out.get(it.key()) : out.putArray(it.key());
                n = arr.addObject();
                n.put("amount", money(it.amount()));
            }
            if (it.code() != null) n.put(typeField, it.code());
            if (it.quantity() != null) {
                // Horas: con decimales; días: entero.
                n.put("quantity", "hora".equals(it.key()) ? money(it.quantity()) : String.valueOf(it.quantity().intValue()));
            }
            if (it.percentage() != null) n.put("percentage", money(it.percentage()));
            if (it.description() != null) n.put("description", trim(it.description(), 200));
        }
        return out;
    }

    /**
     * Facturas: se buscan por reference_code. Para notas y documentos soporte la documentación no trae búsqueda por
     * referencia; se responde "no lo tiene" y el motor reenvía con la MISMA referencia, que Factus no duplica.
     */
    @Override
    public Result query(String externalRef) throws ProviderUnavailableException {
        if (!isBill(externalRef)) return Result.notFound();
        HttpResponse<String> res = call("GET", "/v2/bills?filter[reference_code]=" + URLEncoder.encode(externalRef, StandardCharsets.UTF_8), null, true);
        if (res.statusCode() == 404) return Result.notFound();
        if (res.statusCode() >= 400) return interpret(res);
        JsonNode list = read(res.body()).path("data").path("data");
        if (!list.isArray() || list.isEmpty()) return Result.notFound();
        String number = list.get(0).path("number").asText(null);
        if (number == null) return Result.notFound();
        // El listado no trae el detalle de validación: se consulta el documento por su número.
        return interpret(call("GET", "/v2/bills/" + URLEncoder.encode(number, StandardCharsets.UTF_8), null, true));
    }

    /** Las referencias de documentos de venta son "T{tienda}-S{venta}-..." o las del documento de prueba. */
    static boolean isBill(String ref) {
        return ref.contains("-S") && !ref.contains("-NC-") && !ref.contains("-ND-") && !ref.contains("-NAS-") || ref.contains("-TEST-");
    }

    /** Respuesta → resultado. Las "Notificación" de la DIAN no son rechazos. */
    Result interpret(HttpResponse<String> res) throws ProviderUnavailableException {
        int code = res.statusCode();
        JsonNode body = read(res.body());
        if (code >= 500 || code == 429) throw new ProviderUnavailableException("Factus respondió " + code + ": " + message(body));
        if (code == 409) {
            // Hay un documento con esa referencia pendiente de enviar a la DIAN: se consulta más tarde.
            return new Result(Outcome.PROCESSING, null, null, null, List.of());
        }
        if (code == 402) {
            return new Result(Outcome.REJECTED, null, null, null,
                    List.of(new ProviderError("FACTUS_402", "Factus pide pago: la bolsa de documentos se agotó o venció. " + message(body))));
        }
        if (code >= 400) {
            List<ProviderError> errs = errors(body.path("errors"));
            if (errs.isEmpty()) errs = errors(body.path("data").path("errors"));
            if (errs.isEmpty()) errs = List.of(new ProviderError("FACTUS_" + code, message(body)));
            return new Result(Outcome.REJECTED, null, null, null, errs);
        }
        JsonNode data = body.path("data");
        if (data.has("bill")) data = data.path("bill"); // algunas respuestas anidan el documento
        String number = text(data, "number");
        String cufe = firstText(data, "cufe", "cude", "cuds", "cune");
        String qr = data.path("links").path("qr").asText(null);
        if (qr == null) qr = text(data, "qr");
        boolean validated = data.path("is_validated").asBoolean(false) || !data.path("validated_at").isMissingNode() && !data.path("validated_at").isNull();
        List<ProviderError> errs = errors(data.path("errors"));
        List<ProviderError> rejections = errs.stream().filter(e -> !e.message().toLowerCase().contains("notificaci")).toList();
        if (validated) return new Result(Outcome.ACCEPTED, number, cufe, qr, List.of(), number);
        if (!rejections.isEmpty() && rejections.stream().anyMatch(e -> e.message().toLowerCase().contains("rechazo"))) {
            return new Result(Outcome.REJECTED, number, null, null, rejections, number);
        }
        // Recibido y aún no validado por la DIAN (demora): se consulta después con la misma referencia.
        return new Result(Outcome.PROCESSING, number, cufe, qr, List.of(), number);
    }

    // =================================================================== Armado del cuerpo

    ObjectNode body(Payload d) {
        ObjectNode b = json.createObjectNode();
        b.put("reference_code", d.externalRef());
        b.put("observation", trim(d.reason(), 500));
        String type = d.documentType();
        boolean support = EInvoiceService.SOPORTE.equals(type) || EInvoiceService.NOTA_AJUSTE_SOPORTE.equals(type);
        if (EInvoiceService.NOTA_CREDITO.equals(type) || EInvoiceService.NOTA_DEBITO.equals(type)) {
            b.put("correction_concept_code", d.noteConcept() == null ? "2" : d.noteConcept());
            b.put("customization_id", EInvoiceService.NOTA_CREDITO.equals(type) ? "20" : "30");
            b.put("bill_number", d.originalNumber());
        } else if (EInvoiceService.NOTA_AJUSTE_SOPORTE.equals(type)) {
            b.put("correction_concept_code", d.noteConcept() == null ? "5" : d.noteConcept());
            b.put("support_document_number", d.originalNumber());
        } else if (!support) {
            b.put("document", "01");
            b.put("operation_type", "10");
        }
        b.put("send_email", !support);

        ArrayNode pays = b.putArray("payment_details");
        ObjectNode pay = pays.addObject();
        boolean credit = "CREDITO".equals(d.paymentForm());
        pay.put("payment_form", credit ? "2" : "1");
        pay.put("payment_method_code", methodCode(d.paymentMethod()));
        pay.put("amount", money(d.total()));
        if (credit && d.dueDate() != null) pay.put("due_date", d.dueDate().toString());

        if (support) b.set("provider", party(d.buyer(), true));
        else if (!EInvoiceService.NOTA_CREDITO.equals(type) && !EInvoiceService.NOTA_DEBITO.equals(type)) b.set("customer", party(d.buyer(), false));

        ArrayNode items = b.putArray("items");
        int i = 1;
        for (Line l : d.lines()) {
            ObjectNode it = items.addObject();
            it.put("code_reference", "L" + i++);
            it.put("name", trim(l.description(), 300));
            BigDecimal qty = l.quantity() == null || l.quantity().signum() == 0 ? BigDecimal.ONE : l.quantity();
            it.put("quantity", qty.setScale(2, RoundingMode.HALF_UP).toPlainString());
            // Precio unitario neto (sin IVA y ya con el descuento): Factus calcula el impuesto encima.
            BigDecimal net = l.total().subtract(l.tax() == null ? BigDecimal.ZERO : l.tax());
            it.put("price", net.divide(qty, 2, RoundingMode.HALF_UP).toPlainString());
            it.put("discount_rate", "0.00");
            it.put("unit_measure_code", l.unitCode() == null ? "94" : l.unitCode());
            it.put("standard_code", "999");
            ArrayNode taxes = it.putArray("taxes");
            ObjectNode tax = taxes.addObject();
            tax.put("code", "01");
            tax.put("rate", money(l.taxRate() == null ? BigDecimal.ZERO : l.taxRate()));
            if ("EXCLUIDO".equals(l.taxClass()) || "NO_RESPONSABLE".equals(l.taxClass())) tax.put("is_excluded", true);
        }
        return b;
    }

    /** Comprador (o proveedor en el documento soporte). Consumidor final: cédula 222222222222 y "Consumidor final". */
    ObjectNode party(Party p, boolean provider) {
        ObjectNode c = json.createObjectNode();
        boolean company = "JURIDICA".equals(p.personType());
        c.put("identification_document_code", p.idTypeCode() == null ? "13" : p.idTypeCode());
        c.put("identification", p.idNumber() == null ? "" : p.idNumber().replaceAll("\\D", ""));
        if (p.dv() != null) c.put("dv", p.dv());
        c.put("legal_organization_code", company ? "1" : "2");
        if (company) c.put("company", p.name());
        c.put("names", p.name());
        if (!provider) {
            c.put("tribute_code", p.tributeCode() == null ? "ZZ" : p.tributeCode());
            ArrayNode r = c.putArray("responsibilities");
            (p.responsibilities() == null || p.responsibilities().isEmpty() ? List.of("R-99-PN") : p.responsibilities()).forEach(r::add);
        }
        if (p.address() != null) c.put("address", p.address());
        if (p.email() != null) c.put("email", p.email());
        if (p.municipalityCode() != null) c.put("municipality_code", p.municipalityCode());
        c.put("country_code", "CO");
        return c;
    }

    /** Medio de pago de Ecomerxio POS → código de Factus/DIAN. */
    static String methodCode(String method) {
        if (method == null) return "1";
        return switch (method) {
            case "EFECTIVO" -> "10";
            case "TARJETA" -> "48";
            case "TRANSFERENCIA", "NEQUI", "DAVIPLATA", "BREB" -> "47";
            case "FIADO" -> "1";
            default -> "ZZZ";
        };
    }

    // =================================================================== HTTP y token

    private HttpResponse<String> call(String method, String path, String body, boolean retryOn401) throws ProviderUnavailableException {
        String token = token();
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + path)).timeout(Duration.ofSeconds(40))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json");
        if (body != null) rb.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        else rb.method(method, HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> res = exchange(rb.build());
        if (res.statusCode() == 401 && retryOn401) {
            synchronized (this) {
                accessToken = null;
                expiresAt = Instant.EPOCH;
            }
            return call(method, path, body, false);
        }
        return res;
    }

    private synchronized String token() throws ProviderUnavailableException {
        if (accessToken != null && Instant.now().isBefore(expiresAt.minusSeconds(60))) return accessToken;
        if (refreshToken != null) {
            HttpResponse<String> r = form(Map.of("grant_type", "refresh_token", "client_id", cfg.clientId(), "client_secret", cfg.clientSecret(),
                    "refresh_token", refreshToken), accessToken);
            if (r.statusCode() == 200) return keep(read(r.body()));
            refreshToken = null;
        }
        HttpResponse<String> r = form(Map.of("grant_type", "password", "client_id", cfg.clientId(), "client_secret", cfg.clientSecret(),
                "username", cfg.username(), "password", cfg.password()), null);
        if (r.statusCode() != 200) {
            if (r.statusCode() >= 500) throw new ProviderUnavailableException("Factus no entregó el token (" + r.statusCode() + ")");
            throw new IllegalStateException("Factus rechazó las credenciales (" + r.statusCode() + "): revisa FACTUS_* en la configuración");
        }
        return keep(read(r.body()));
    }

    private String keep(JsonNode t) {
        accessToken = t.path("access_token").asText();
        refreshToken = t.path("refresh_token").asText(null);
        expiresAt = Instant.now().plusSeconds(t.path("expires_in").asLong(600));
        return accessToken;
    }

    private HttpResponse<String> form(Map<String, String> fields, String bearer) throws ProviderUnavailableException {
        StringBuilder sb = new StringBuilder();
        fields.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : "&").append(k).append('=').append(URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8)));
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + "/oauth/token")).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString()));
        if (bearer != null) rb.header("Authorization", "Bearer " + bearer);
        return exchange(rb.build());
    }

    private HttpResponse<String> exchange(HttpRequest req) throws ProviderUnavailableException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ProviderUnavailableException("Sin respuesta de Factus: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("Envío interrumpido");
        }
    }

    // =================================================================== utilidades

    private JsonNode read(String s) {
        try {
            return s == null || s.isBlank() ? json.createObjectNode() : json.readTree(s);
        } catch (IOException e) {
            log.warn("Respuesta de Factus que no es JSON: {}", s.length() > 300 ? s.substring(0, 300) : s);
            return json.createObjectNode().put("message", s.length() > 300 ? s.substring(0, 300) : s);
        }
    }

    /** "errors" puede venir como objeto {regla: mensaje} o {campo: [mensajes]} o como lista. */
    private static List<ProviderError> errors(JsonNode e) {
        List<ProviderError> out = new ArrayList<>();
        if (e == null || e.isMissingNode() || e.isNull()) return out;
        if (e.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = e.fields();
            while (it.hasNext()) {
                var f = it.next();
                if (f.getValue().isArray()) f.getValue().forEach(m -> out.add(new ProviderError(f.getKey(), m.asText())));
                else out.add(new ProviderError(f.getKey(), f.getValue().asText()));
            }
        } else if (e.isArray()) {
            e.forEach(m -> out.add(new ProviderError(m.path("code").asText("ERROR"), m.has("message") ? m.path("message").asText() : m.asText())));
        }
        return out;
    }

    private static String message(JsonNode b) {
        return b.path("message").asText("sin detalle");
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String firstText(JsonNode n, String... fields) {
        for (String f : fields) {
            String v = text(n, f);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String trim(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
