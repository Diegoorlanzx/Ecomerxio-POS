package com.tiendaposs.tenant;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Objects;

/** Datos generales de la tienda: nombre, logo, contacto, moneda y zona horaria. */
@RestController
@RequestMapping("/api/store")
public class StoreController {

    private static final int MAX_LOGO_CHARS = 400_000;

    private final TenantRepository tenants;
    private final AuditService audit;

    public StoreController(TenantRepository tenants, AuditService audit) {
        this.tenants = tenants;
        this.audit = audit;
    }

    public record StoreDto(Long id, String name, String legalName, String taxId, String taxRegime, String address,
                           String city, String phone, String whatsapp, String email, String logo, String currency,
                           String timezone) {
        static StoreDto of(Tenant t) {
            return new StoreDto(t.getId(), t.getName(), t.getLegalName(), t.getTaxId(), t.getTaxRegime(),
                    t.getAddress(), t.getCity(), t.getPhone(), t.getWhatsapp(), t.getEmail(), t.getLogo(),
                    t.getCurrency(), t.getTimezone());
        }
    }

    public record StoreRequest(
            @NotBlank @Size(max = 120, message = "máximo 120 caracteres") String name,
            @Size(max = 160, message = "máximo 160 caracteres") String legalName,
            @Size(max = 30, message = "máximo 30 caracteres") String taxId,
            @Size(max = 40, message = "máximo 40 caracteres") String taxRegime,
            @Size(max = 200, message = "máximo 200 caracteres") String address,
            @Size(max = 80, message = "máximo 80 caracteres") String city,
            @Size(max = 30, message = "máximo 30 caracteres") String phone,
            @Size(max = 30, message = "máximo 30 caracteres") String whatsapp,
            @Email(message = "no es un correo válido") String email,
            String logo,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "usa un código de 3 letras, p. ej. COP") String currency,
            @NotBlank String timezone) {
    }

    @GetMapping
    public StoreDto get() {
        return StoreDto.of(current());
    }

    @PutMapping
    @Transactional
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public StoreDto update(@Valid @RequestBody StoreRequest req) {
        Tenant t = current();
        try {
            Currency.getInstance(req.currency());
        } catch (IllegalArgumentException e) {
            throw ApiException.field("currency", "no es una moneda válida");
        }
        try {
            ZoneId.of(req.timezone());
        } catch (Exception e) {
            throw ApiException.field("timezone", "no es una zona horaria válida");
        }
        String logo = blankToNull(req.logo());
        if (logo != null) {
            if (!logo.matches("^data:image/(png|jpeg|webp|svg\\+xml);base64,[A-Za-z0-9+/=]+$")) {
                throw ApiException.field("logo", "debe ser una imagen PNG, JPG, WEBP o SVG");
            }
            if (logo.length() > MAX_LOGO_CHARS) throw ApiException.field("logo", "la imagen es muy pesada (máx. 300 KB)");
        }
        boolean logoChanged = !Objects.equals(logo, t.getLogo());
        t.setName(req.name().trim());
        t.setLegalName(blankToNull(req.legalName()));
        t.setTaxId(blankToNull(req.taxId()));
        t.setTaxRegime(blankToNull(req.taxRegime()));
        t.setAddress(blankToNull(req.address()));
        t.setCity(blankToNull(req.city()));
        t.setPhone(blankToNull(req.phone()));
        t.setWhatsapp(blankToNull(req.whatsapp()));
        t.setEmail(blankToNull(req.email()));
        t.setLogo(logo);
        t.setCurrency(req.currency());
        t.setTimezone(req.timezone());
        audit.sensitive("CONFIG_TIENDA", "TENANT", t.getId(),
                "Actualizó los datos generales de la tienda" + (logoChanged ? " (incluido el logo)" : ""));
        return StoreDto.of(t);
    }

    private Tenant current() {
        return tenants.findById(TenantContext.require()).orElseThrow(() -> ApiException.notFound("Tienda no encontrada"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
