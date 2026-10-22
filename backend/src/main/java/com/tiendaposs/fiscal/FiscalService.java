package com.tiendaposs.fiscal;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogRepos.CategoryRepository;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.fiscal.FiscalRepos.FiscalProfileRepository;
import com.tiendaposs.fiscal.FiscalRepos.ResolutionRepository;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.tenant.TenantRepository;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Fase 13A: todo lo que tiene que estar listo antes de emitir el primer documento electrónico. Aquí no se emite nada;
 * solo se guardan y validan los datos, y {@link #readiness()} dice qué falta y dónde corregirlo.
 */
@Service
public class FiscalService {

    /** Avisar cuando la resolución vence en estos días o menos. */
    public static final int EXPIRY_WARNING_DAYS = 30;
    /** Avisar cuando se ha usado este porcentaje del rango. */
    public static final int RANGE_WARNING_PCT = 80;

    private final FiscalProfileRepository profiles;
    private final ResolutionRepository resolutions;
    private final TenantRepository tenants;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final AuditService audit;
    private final com.tiendaposs.einvoice.EInvoiceRepos.DocumentRepository documents;

    public FiscalService(FiscalProfileRepository profiles, ResolutionRepository resolutions, TenantRepository tenants,
                         ProductRepository products, CategoryRepository categories, AuditService audit,
                         com.tiendaposs.einvoice.EInvoiceRepos.DocumentRepository documents) {
        this.documents = documents;
        this.profiles = profiles;
        this.resolutions = resolutions;
        this.tenants = tenants;
        this.products = products;
        this.categories = categories;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ Catálogos

    public record Option(String code, String label) {
    }

    public record Catalogs(List<Option> idTypes, List<Option> responsibilities, List<Option> documentTypes, List<Option> taxClasses) {
    }

    public Catalogs catalogs() {
        return new Catalogs(
                FiscalCodes.ID_TYPE_LABELS.entrySet().stream().map(e -> new Option(e.getKey(), e.getValue())).toList(),
                FiscalCodes.RESPONSIBILITIES.entrySet().stream().map(e -> new Option(e.getKey(), e.getKey() + " · " + e.getValue())).toList(),
                FiscalCodes.DOCUMENT_TYPES.entrySet().stream().map(e -> new Option(e.getKey(), e.getValue())).toList(),
                List.of(new Option("GRAVADO", "Gravado (tiene IVA)"), new Option("EXENTO", "Exento (IVA 0 %, con derecho a devolución)"),
                        new Option("EXCLUIDO", "Excluido (no causa IVA)")));
    }

    // ------------------------------------------------------------------ Perfil del comercio

    public record ProfileDto(String personType, String idType, String idNumber, String dv, String expectedDv, String legalName,
                             String firstNames, String lastNames, String tradeName, String registrationCode,
                             Boolean ivaResponsible, List<String> responsibilities, String ciiu, String address,
                             String municipalityCode, String municipalityName, String departmentName, String postalCode,
                             String invoicingEmail, String phone, boolean registeredAsIssuer, LocalDate issuerRegisteredOn,
                             String providerName, boolean providerAuthorized, String environment) {
        static ProfileDto of(FiscalProfile p) {
            Character exp = "NIT".equals(p.idType) ? Nit.dv(p.idNumber) : null;
            return new ProfileDto(p.personType, p.idType, p.idNumber, p.dv, exp == null ? null : exp.toString(), p.legalName,
                    p.firstNames, p.lastNames, p.tradeName, p.registrationCode,
                    p.ivaResponsible, csv(p.responsibilities), p.ciiu, p.address, p.municipalityCode,
                    p.municipalityName, p.departmentName, p.postalCode, p.invoicingEmail, p.phone, p.registeredAsIssuer,
                    p.issuerRegisteredOn, p.providerName, p.providerAuthorized, p.environment);
        }
    }

    public record ProfileRequest(
            @Pattern(regexp = "NATURAL|JURIDICA", message = "elige persona natural o jurídica") String personType,
            @Pattern(regexp = "NIT|CC|CE|PP|NIT_EXT", message = "tipo de documento no válido") String idType,
            @Size(max = 20, message = "máximo 20 caracteres") String idNumber,
            @Size(max = 1, message = "es un solo dígito") String dv,
            @Size(max = 160, message = "máximo 160 caracteres") String legalName,
            @Size(max = 80, message = "máximo 80 caracteres") String firstNames,
            @Size(max = 80, message = "máximo 80 caracteres") String lastNames,
            @Size(max = 160, message = "máximo 160 caracteres") String tradeName,
            @Pattern(regexp = "^$|[0-9A-Za-z-]{1,20}", message = "solo números, letras o guion (hasta 20)") String registrationCode,
            Boolean ivaResponsible,
            List<String> responsibilities,
            @Pattern(regexp = "^$|[0-9]{4}", message = "son 4 dígitos (código CIIU)") String ciiu,
            @Size(max = 200, message = "máximo 200 caracteres") String address,
            @Pattern(regexp = "^$|[0-9]{5}", message = "son 5 dígitos (código DANE del municipio)") String municipalityCode,
            @Size(max = 80, message = "máximo 80 caracteres") String municipalityName,
            @Size(max = 80, message = "máximo 80 caracteres") String departmentName,
            @Pattern(regexp = "^$|[0-9]{6}", message = "son 6 dígitos") String postalCode,
            @Email(message = "no es un correo válido") @Size(max = 160) String invoicingEmail,
            @Size(max = 30, message = "máximo 30 caracteres") String phone,
            Boolean registeredAsIssuer,
            LocalDate issuerRegisteredOn,
            @Size(max = 80, message = "máximo 80 caracteres") String providerName,
            Boolean providerAuthorized,
            @Pattern(regexp = "PRUEBAS|PRODUCCION", message = "ambiente no válido") String environment) {
    }

    /** El perfil de la tienda; la primera vez se arma con los datos generales que ya tiene. */
    @Transactional
    public FiscalProfile current() {
        Long t = TenantContext.require();
        return profiles.findById(t).orElseGet(() -> {
            Tenant tenant = tenants.findById(t).orElseThrow();
            FiscalProfile p = new FiscalProfile();
            p.tenantId = t;
            String digits = Nit.digits(tenant.getTaxId());
            // "900123456-7" → número y DV por separado
            String raw = tenant.getTaxId() == null ? "" : tenant.getTaxId();
            if (raw.contains("-") && digits.length() > 1) {
                p.idNumber = digits.substring(0, digits.length() - 1);
                p.dv = digits.substring(digits.length() - 1);
            } else {
                p.idNumber = digits.isEmpty() ? null : digits;
            }
            p.legalName = tenant.getLegalName();
            p.tradeName = tenant.getName();
            p.address = tenant.getAddress();
            p.municipalityName = tenant.getCity();
            p.invoicingEmail = tenant.getEmail();
            p.phone = tenant.getPhone();
            return profiles.save(p);
        });
    }

    public ProfileDto profile() {
        return ProfileDto.of(current());
    }

    @Transactional
    public ProfileDto saveProfile(ProfileRequest r) {
        FiscalProfile p = current();
        String idType = r.idType() == null ? "NIT" : r.idType();
        String number = Nit.digits(r.idNumber());
        String dv = blank(r.dv());
        if ("NIT".equals(idType) && !number.isEmpty()) {
            if (dv == null) throw ApiException.field("dv", "escribe el dígito de verificación (es " + Nit.dv(number) + ")");
            if (!Nit.matches(number, dv)) throw ApiException.field("dv", "no corresponde a ese NIT: debería ser " + Nit.dv(number));
        }
        List<String> resp = r.responsibilities() == null ? List.of() : r.responsibilities().stream().distinct().toList();
        for (String code : resp) {
            if (!FiscalCodes.RESPONSIBILITIES.containsKey(code)) throw ApiException.field("responsibilities", "código no válido: " + code);
        }
        if (resp.contains("R-99-PN") && resp.size() > 1) {
            throw ApiException.field("responsibilities", "\"No aplica\" no se combina con otras responsabilidades");
        }
        if (Boolean.TRUE.equals(r.providerAuthorized()) && blank(r.providerName()) == null) {
            throw ApiException.field("providerName", "escribe a qué proveedor tecnológico autorizaste");
        }
        String municipality = blank(r.municipalityCode());
        if (municipality != null && !Municipalities.exists(municipality)) {
            throw ApiException.field("municipalityCode", "no es un código DANE de municipio válido");
        }
        p.personType = blank(r.personType());
        p.idType = idType;
        p.idNumber = number.isEmpty() ? null : number;
        p.dv = "NIT".equals(idType) ? dv : null;
        boolean natural = "NATURAL".equals(p.personType);
        p.firstNames = natural ? blank(r.firstNames()) : null;
        p.lastNames = natural ? blank(r.lastNames()) : null;
        // Persona natural: el nombre completo sale de nombres + apellidos (es el que va en el recibo y el documento).
        p.legalName = natural && p.firstNames != null && p.lastNames != null ? p.firstNames + " " + p.lastNames : blank(r.legalName());
        p.tradeName = blank(r.tradeName());
        p.registrationCode = blank(r.registrationCode());
        p.ivaResponsible = r.ivaResponsible();
        p.responsibilities = resp.isEmpty() ? null : String.join(",", resp);
        p.ciiu = blank(r.ciiu());
        p.address = blank(r.address());
        p.municipalityCode = municipality;
        Municipalities.Municipality m = Municipalities.get(municipality);
        p.municipalityName = m != null ? m.name() : blank(r.municipalityName());
        p.departmentName = m != null ? m.department() : blank(r.departmentName());
        p.postalCode = blank(r.postalCode());
        p.invoicingEmail = blank(r.invoicingEmail());
        p.phone = blank(r.phone());
        p.registeredAsIssuer = Boolean.TRUE.equals(r.registeredAsIssuer());
        p.issuerRegisteredOn = p.registeredAsIssuer ? r.issuerRegisteredOn() : null;
        p.providerName = blank(r.providerName());
        p.providerAuthorized = Boolean.TRUE.equals(r.providerAuthorized());
        if (r.environment() != null && !r.environment().equals(p.environment)) {
            if ("PRODUCCION".equals(r.environment()) && !profileComplete(p)) {
                throw ApiException.field("environment", "completa los datos fiscales antes de pasar a producción");
            }
            audit.sensitive("FACTURACION_AMBIENTE", "TENANT", p.tenantId, "Cambió el ambiente de facturación a " + r.environment());
            p.environment = r.environment();
        }
        audit.sensitive("FACTURACION_DATOS", "TENANT", p.tenantId, "Actualizó los datos fiscales del negocio");
        return ProfileDto.of(profiles.save(p));
    }

    /** Qué datos obligatorios del emisor faltan (vacío = completo). */
    public List<String> missingProfileData(FiscalProfile p) {
        List<String> m = new ArrayList<>();
        if (p.personType == null) m.add("tipo de persona");
        if (p.idNumber == null) m.add("número de identificación");
        else if ("NIT".equals(p.idType) && !Nit.matches(p.idNumber, p.dv)) m.add("dígito de verificación correcto");
        if ("NATURAL".equals(p.personType)) {
            if (p.firstNames == null || p.lastNames == null) m.add("nombres y apellidos");
        } else if (p.legalName == null) m.add("razón social o nombre");
        if (p.ivaResponsible == null) m.add("si es responsable de IVA");
        if (p.responsibilities == null) m.add("responsabilidades fiscales");
        if (p.address == null) m.add("dirección");
        if (p.municipalityCode == null) m.add("municipio (código DANE)");
        else if (!Municipalities.exists(p.municipalityCode)) m.add("municipio con código DANE válido");
        if (p.invoicingEmail == null) m.add("correo de facturación");
        return m;
    }

    private boolean profileComplete(FiscalProfile p) {
        return missingProfileData(p).isEmpty();
    }

    // ------------------------------------------------------------------ Resoluciones de numeración

    public record ResolutionDto(Long id, String documentType, String documentLabel, String resolutionNumber, LocalDate resolutionDate,
                                String prefix, long rangeFrom, long rangeTo, long nextNumber, long used, long remaining,
                                int usedPct, LocalDate validFrom, LocalDate validTo, long daysLeft, String environment,
                                boolean hasTechnicalKey, boolean active, String status) {
        static ResolutionDto of(NumberingResolution r, LocalDate today) {
            int pct = (int) (r.used() * 100 / Math.max(1, r.size()));
            long days = ChronoUnit.DAYS.between(today, r.validTo);
            return new ResolutionDto(r.id, r.documentType, FiscalCodes.DOCUMENT_TYPES.get(r.documentType), r.resolutionNumber,
                    r.resolutionDate, r.prefix, r.rangeFrom, r.rangeTo, r.nextNumber, r.used(), r.remaining(), pct,
                    r.validFrom, r.validTo, days, r.environment, r.technicalKey != null, r.active, FiscalService.status(r, today));
        }
    }

    /** VIGENTE, POR_VENCER, POR_AGOTAR, VENCIDA, AGOTADA, NO_INICIA o INACTIVA. */
    static String status(NumberingResolution r, LocalDate today) {
        if (!r.active) return "INACTIVA";
        if (today.isAfter(r.validTo)) return "VENCIDA";
        if (today.isBefore(r.validFrom)) return "NO_INICIA";
        if (r.remaining() == 0) return "AGOTADA";
        if (ChronoUnit.DAYS.between(today, r.validTo) <= EXPIRY_WARNING_DAYS) return "POR_VENCER";
        if (r.used() * 100 >= (long) RANGE_WARNING_PCT * r.size()) return "POR_AGOTAR";
        return "VIGENTE";
    }

    public record ResolutionRequest(
            @NotBlank(message = "elige el tipo de documento") String documentType,
            @NotBlank(message = "escribe el número de la resolución") @Size(max = 40) String resolutionNumber,
            LocalDate resolutionDate,
            @Pattern(regexp = "^$|[A-Z0-9]{1,4}", message = "hasta 4 letras o números en mayúscula") String prefix,
            @NotNull(message = "es obligatorio") @Positive(message = "debe ser mayor que cero") Long rangeFrom,
            @NotNull(message = "es obligatorio") @Positive(message = "debe ser mayor que cero") Long rangeTo,
            @Positive(message = "debe ser mayor que cero") Long nextNumber,
            @NotNull(message = "es obligatoria") LocalDate validFrom,
            @NotNull(message = "es obligatoria") LocalDate validTo,
            @Pattern(regexp = "PRUEBAS|PRODUCCION", message = "ambiente no válido") String environment,
            @Size(max = 120) String technicalKey,
            Boolean active) {
    }

    public List<ResolutionDto> resolutions() {
        LocalDate today = today();
        return resolutions.findByTenantIdOrderByActiveDescValidToDesc(TenantContext.require()).stream()
                .map(r -> ResolutionDto.of(r, today)).toList();
    }

    @Transactional
    public ResolutionDto saveResolution(Long id, ResolutionRequest r) {
        Long t = TenantContext.require();
        if (!FiscalCodes.DOCUMENT_TYPES.containsKey(r.documentType())) throw ApiException.field("documentType", "tipo de documento no válido");
        if (r.rangeTo() < r.rangeFrom()) throw ApiException.field("rangeTo", "debe ser mayor o igual que el número inicial");
        if (r.validTo().isBefore(r.validFrom())) throw ApiException.field("validTo", "debe ser posterior a la fecha de inicio");
        NumberingResolution res = id == null ? new NumberingResolution()
                : resolutions.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Resolución no encontrada"));
        long next = r.nextNumber() == null ? (id == null ? r.rangeFrom() : res.nextNumber) : r.nextNumber();
        if (next < r.rangeFrom() || next > r.rangeTo() + 1) {
            throw ApiException.field("nextNumber", "debe estar dentro del rango autorizado");
        }
        // Una resolución con documentos emitidos no puede achicarse por debajo de lo usado ni devolver el consecutivo.
        if (id != null && next < res.nextNumber) {
            throw ApiException.field("nextNumber", "no puede ser menor que el actual (" + res.nextNumber + "): esos números ya pudieron usarse");
        }
        String env = r.environment() == null ? "PRUEBAS" : r.environment();
        boolean active = r.active() == null || r.active();
        String prefix = blank(r.prefix());
        if (active) {
            for (NumberingResolution o : resolutions.findByTenantIdAndActiveTrue(t)) {
                if (o.id.equals(res.id) || !o.documentType.equals(r.documentType()) || !o.environment.equals(env)) continue;
                boolean samePrefix = Objects.equals(o.prefix, prefix);
                boolean overlap = r.rangeFrom() <= o.rangeTo && o.rangeFrom <= r.rangeTo();
                if (samePrefix && overlap) {
                    throw ApiException.field("rangeFrom", "se cruza con la resolución " + o.resolutionNumber + " del mismo prefijo");
                }
            }
        }
        res.tenantId = t;
        res.documentType = r.documentType();
        res.resolutionNumber = r.resolutionNumber().trim();
        res.resolutionDate = r.resolutionDate();
        res.prefix = prefix;
        res.rangeFrom = r.rangeFrom();
        res.rangeTo = r.rangeTo();
        res.nextNumber = next;
        res.validFrom = r.validFrom();
        res.validTo = r.validTo();
        res.environment = env;
        if (r.technicalKey() != null) res.technicalKey = blank(r.technicalKey());
        res.active = active;
        NumberingResolution saved = resolutions.save(res);
        audit.sensitive(id == null ? "RESOLUCION_CREADA" : "RESOLUCION_EDITADA", "RESOLUTION", saved.id,
                (id == null ? "Registró" : "Editó") + " la resolución " + saved.resolutionNumber + " ("
                        + FiscalCodes.DOCUMENT_TYPES.get(saved.documentType) + ", " + (saved.prefix == null ? "" : saved.prefix)
                        + saved.rangeFrom + "–" + saved.rangeTo + ")");
        return ResolutionDto.of(saved, today());
    }

    /** Para las alertas: resoluciones activas que vencen pronto o se están agotando. */
    public List<ResolutionDto> resolutionsNeedingAttention(Long tenantId, LocalDate today) {
        return resolutions.findByTenantIdAndActiveTrue(tenantId).stream().map(r -> ResolutionDto.of(r, today))
                .filter(d -> Set.of("POR_VENCER", "POR_AGOTAR", "VENCIDA", "AGOTADA").contains(d.status())).toList();
    }

    // ------------------------------------------------------------------ Clasificación tributaria de productos

    public record ProductTaxRow(Long id, String sku, String name, String category, String unit, String unitCode,
                                String taxName, BigDecimal taxRate, String taxClass, String effectiveClass, boolean pending) {
    }

    /** Productos que se venden (no los padres de variantes); con tarifa 0 y sin clasificar = pendientes. */
    public List<ProductTaxRow> productTaxes(boolean onlyPending) {
        Long t = TenantContext.require();
        Map<Long, String> cats = categories.findByTenantIdOrderBySortOrderAscNameAsc(t).stream()
                .collect(Collectors.toMap(c -> c.id, c -> c.name));
        return products.findByTenantIdAndStatusOrderByNameAsc(t, "ACTIVE").stream()
                .filter(p -> !"PADRE".equals(p.productType))
                .map(p -> row(p, cats))
                .filter(r -> !onlyPending || r.pending())
                .toList();
    }

    private static ProductTaxRow row(Product p, Map<Long, String> cats) {
        boolean gravado = p.taxRate != null && p.taxRate.signum() > 0;
        String effective = gravado ? "GRAVADO" : p.taxClass;
        return new ProductTaxRow(p.id, p.sku, p.name, cats.get(p.categoryId), p.unit, FiscalCodes.UNITS.get(p.unit),
                p.taxName, p.taxRate, p.taxClass, effective, effective == null);
    }

    public record ClassifyRequest(@NotEmpty(message = "elige al menos un producto") List<Long> productIds,
                                  @NotBlank @Pattern(regexp = "EXENTO|EXCLUIDO", message = "elige exento o excluido") String taxClass) {
    }

    /** Solo para productos con tarifa 0: los que tienen IVA ya son gravados y no se tocan. */
    @Transactional
    public Map<String, Integer> classify(ClassifyRequest r) {
        Long t = TenantContext.require();
        int changed = 0, skipped = 0;
        for (Product p : products.findAllById(r.productIds())) {
            if (!t.equals(p.tenantId)) continue;
            if (p.taxRate != null && p.taxRate.signum() > 0) {
                skipped++;
                continue;
            }
            p.taxClass = r.taxClass();
            products.save(p);
            changed++;
        }
        if (changed > 0) {
            audit.log("PRODUCTOS_CLASIFICADOS", "PRODUCT", null, "Marcó " + changed + " producto(s) como " + r.taxClass().toLowerCase());
        }
        return Map.of("changed", changed, "skippedTaxed", skipped);
    }

    // ------------------------------------------------------------------ Preparación (asistente)

    public record Step(String key, String title, String status, String detail, String action) {
    }

    public record Readiness(boolean ready, int done, int total, List<Step> steps, String environment, boolean invoicingEnabled,
                            /** Fase 16A: OBLIGADO o A_PEDIDO. */ String invoicingMode) {
    }

    /** Cada paso: OK, PENDIENTE o BLOQUEADO (depende de algo que aún no existe). */
    public Readiness readiness() {
        FiscalProfile p = current();
        LocalDate today = today();
        List<Step> steps = new ArrayList<>();

        List<String> missing = missingProfileData(p);
        steps.add(new Step("DATOS", "Datos fiscales del negocio", missing.isEmpty() ? "OK" : "PENDIENTE",
                missing.isEmpty() ? "Completos." : "Falta: " + String.join(", ", missing) + ".", "datos"));

        steps.add(new Step("INSCRIPCION", "Inscripción como facturador electrónico",
                p.registeredAsIssuer ? "OK" : "PENDIENTE",
                p.registeredAsIssuer ? "Inscrito" + (p.issuerRegisteredOn == null ? "." : " desde el " + p.issuerRegisteredOn + ".")
                        : "Se hace en el portal de la DIAN con tu usuario. Cuando esté hecho, márcalo en Datos fiscales.", "datos"));

        steps.add(new Step("PROVEEDOR", "Proveedor tecnológico autorizado",
                p.providerAuthorized ? "OK" : "PENDIENTE",
                p.providerAuthorized ? "Autorizaste a " + p.providerName + "."
                        : "En el portal de la DIAN autoriza al proveedor que usa Ecomerxio POS y márcalo en Datos fiscales.", "datos"));

        List<NumberingResolution> active = resolutions.findByTenantIdAndActiveTrue(p.tenantId).stream()
                .filter(r -> r.environment.equals(p.environment)).toList();
        Optional<NumberingResolution> usable = active.stream()
                .filter(r -> Set.of("POS_ELECTRONICO", "FACTURA").contains(r.documentType))
                .filter(r -> Set.of("VIGENTE", "POR_VENCER", "POR_AGOTAR").contains(status(r, today))).findFirst();
        String resDetail;
        if (usable.isPresent()) {
            NumberingResolution r = usable.get();
            String st = status(r, today);
            resDetail = FiscalCodes.DOCUMENT_TYPES.get(r.documentType) + " " + r.resolutionNumber + ": "
                    + switch (st) {
                case "POR_VENCER" -> "vence el " + r.validTo + ". Pide una nueva a tiempo.";
                case "POR_AGOTAR" -> "quedan " + r.remaining() + " números. Pide una ampliación a tiempo.";
                default -> "vigente hasta el " + r.validTo + ", quedan " + r.remaining() + " números.";
            };
        } else {
            resDetail = active.isEmpty() ? "Registra la resolución de numeración (POS electrónico o factura) del ambiente de "
                    + p.environment.toLowerCase() + "." : "Las resoluciones activas están vencidas, agotadas o aún no empiezan.";
        }
        steps.add(new Step("RESOLUCION", "Resolución de numeración", usable.isPresent() ? "OK" : "PENDIENTE", resDetail, "resoluciones"));

        long pending = productTaxes(true).size();
        steps.add(new Step("PRODUCTOS", "Clasificación tributaria de productos", pending == 0 ? "OK" : "PENDIENTE",
                pending == 0 ? "Todos los productos tienen su clasificación."
                        : pending + " producto(s) con IVA 0 % sin clasificar como exentos o excluidos.", "productos"));

        boolean tested = documents.existsByTenantIdAndEnvironmentAndStatus(p.tenantId, p.environment, "ACEPTADO");
        steps.add(new Step("PRUEBA", "Documento de prueba", tested ? "OK" : "PENDIENTE",
                tested ? "Ya hay un documento aceptado en el ambiente de " + p.environment.toLowerCase() + "."
                        : "Emite un documento de prueba para comprobar que todo funciona de punta a punta.", "prueba"));

        int done = (int) steps.stream().filter(s -> "OK".equals(s.status())).count();
        int required = (int) steps.stream().filter(s -> !"BLOQUEADO".equals(s.status())).count();
        return new Readiness(done == required, done, steps.size(), steps, p.environment, p.invoicingEnabled, p.invoicingMode);
    }

    // ------------------------------------------------------------------ utilidades

    private LocalDate today() {
        return LocalDate.now(java.time.ZoneId.of(tenants.findById(TenantContext.require()).map(Tenant::getTimezone).orElse("America/Bogota")));
    }

    static List<String> csv(String s) {
        return s == null || s.isBlank() ? List.of() : Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
