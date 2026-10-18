package com.tiendaposs.fiscal;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Fase 13A: datos fiscales, resoluciones, clasificación tributaria y preparación para facturar. */
@RestController
@RequestMapping("/api/fiscal")
@PreAuthorize("hasAuthority('FACTURACION_CONFIGURAR')")
public class FiscalController {

    private final FiscalService fiscal;

    public FiscalController(FiscalService fiscal) {
        this.fiscal = fiscal;
    }

    @GetMapping("/catalogs")
    public FiscalService.Catalogs catalogs() {
        return fiscal.catalogs();
    }

    @GetMapping("/readiness")
    public FiscalService.Readiness readiness() {
        return fiscal.readiness();
    }

    @GetMapping("/profile")
    public FiscalService.ProfileDto profile() {
        return fiscal.profile();
    }

    @PutMapping("/profile")
    public FiscalService.ProfileDto saveProfile(@Valid @RequestBody FiscalService.ProfileRequest r) {
        return fiscal.saveProfile(r);
    }

    /** Dígito de verificación de un número (para mostrarlo mientras se escribe). */
    @GetMapping("/dv")
    public Map<String, String> dv(@RequestParam String number) {
        Character dv = Nit.dv(number);
        return Map.of("number", Nit.digits(number), "dv", dv == null ? "" : dv.toString());
    }

    @GetMapping("/resolutions")
    public List<FiscalService.ResolutionDto> resolutions() {
        return fiscal.resolutions();
    }

    @PostMapping("/resolutions")
    public FiscalService.ResolutionDto createResolution(@Valid @RequestBody FiscalService.ResolutionRequest r) {
        return fiscal.saveResolution(null, r);
    }

    @PutMapping("/resolutions/{id}")
    public FiscalService.ResolutionDto updateResolution(@PathVariable Long id, @Valid @RequestBody FiscalService.ResolutionRequest r) {
        return fiscal.saveResolution(id, r);
    }

    @GetMapping("/products")
    public List<FiscalService.ProductTaxRow> products(@RequestParam(defaultValue = "false") boolean pending) {
        return fiscal.productTaxes(pending);
    }

    @PostMapping("/products/classify")
    public Map<String, Integer> classify(@Valid @RequestBody FiscalService.ClassifyRequest r) {
        return fiscal.classify(r);
    }
}
