package com.tiendaposs.pharmacy;

import com.tiendaposs.common.ApiException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Droguería: datos de droguería de un producto y libro de fórmulas despachadas. */
@RestController
@RequestMapping("/api")
public class PharmacyController {

    private final PharmacyService pharmacy;

    public PharmacyController(PharmacyService pharmacy) {
        this.pharmacy = pharmacy;
    }

    @PutMapping("/products/{id}/pharmacy")
    @PreAuthorize("hasAuthority('PRODUCTOS_EDITAR')")
    public PharmacyService.PharmacyInfo update(@PathVariable Long id, @RequestBody PharmacyService.PharmacyInfo body) {
        return pharmacy.update(id, body);
    }

    /** Fórmulas despachadas en un rango (máximo un año); con controlled=true, solo medicamentos de control especial. */
    @GetMapping("/pharmacy/prescriptions")
    @PreAuthorize("hasAuthority('REPORTES_VER')")
    public List<PharmacyService.PrescriptionRow> prescriptions(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                               @RequestParam(defaultValue = "false") boolean controlled) {
        if (to.isBefore(from) || from.plusYears(1).isBefore(to)) throw ApiException.badRequest("Elige un rango de hasta un año");
        return pharmacy.prescriptions(from, to, controlled);
    }
}
