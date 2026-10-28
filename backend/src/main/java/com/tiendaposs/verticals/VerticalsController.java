package com.tiendaposs.verticals;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Tipos de negocio, fase 5: mascotas, fórmula óptica, listas de útiles y datos extra del producto (ICA, mayores de edad). */
@RestController
@RequestMapping("/api")
public class VerticalsController {

    private final VerticalsService v;
    private final MembershipService memberships;

    public VerticalsController(VerticalsService v, MembershipService memberships) {
        this.v = v;
        this.memberships = memberships;
    }

    // ---------- Gimnasio ----------

    public record MembershipDaysRequest(Integer days) {
    }

    @PutMapping("/products/{id}/membership")
    @PreAuthorize("hasAuthority('PRODUCTOS_EDITAR')")
    public Map<String, Integer> membershipDays(@PathVariable Long id, @RequestBody MembershipDaysRequest r) {
        Integer d = memberships.setMembershipDays(id, r.days());
        return d == null ? Map.of() : Map.of("membershipDays", d);
    }

    @GetMapping("/memberships")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public List<MembershipService.Member> members(@RequestParam(required = false) String q) {
        return memberships.search(q);
    }

    @GetMapping("/memberships/expiring")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public List<MembershipService.Member> expiring(@RequestParam(defaultValue = "7") int days) {
        return memberships.expiring(days);
    }

    @PostMapping("/memberships/{customerId}/checkin")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public MembershipService.CheckinResult checkin(@PathVariable Long customerId) {
        return memberships.checkin(customerId);
    }

    @PutMapping("/products/{id}/extras")
    @PreAuthorize("hasAuthority('PRODUCTOS_EDITAR')")
    public VerticalsService.ProductExtras extras(@PathVariable Long id, @RequestBody VerticalsService.ProductExtras r) {
        return v.updateProduct(id, r);
    }

    // ---------- Veterinaria ----------

    @GetMapping("/pets")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public List<VerticalsService.Pet> pets(@RequestParam(required = false) String q) {
        return v.pets(q);
    }

    @PostMapping("/pets")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public Map<String, Long> createPet(@RequestBody VerticalsService.PetRequest r) {
        return Map.of("id", v.createPet(r));
    }

    @PostMapping("/pets/{id}/vaccines")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public Map<String, Boolean> vaccine(@PathVariable Long id, @RequestBody VerticalsService.VaccineRequest r) {
        v.addVaccine(id, r);
        return Map.of("ok", true);
    }

    @GetMapping("/pets/vaccines/due")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public List<VerticalsService.DueVaccine> due(@RequestParam(defaultValue = "15") int days) {
        return v.dueVaccines(days);
    }

    // ---------- Óptica ----------

    @GetMapping("/optical-prescriptions")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public List<VerticalsService.Optical> opticals(@RequestParam(required = false) String q) {
        return v.opticals(q);
    }

    @PostMapping("/optical-prescriptions")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public Map<String, Long> createOptical(@RequestBody VerticalsService.OpticalRequest r) {
        return Map.of("id", v.createOptical(r));
    }

    // ---------- Papelería ----------

    @GetMapping("/school-lists")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public List<VerticalsService.SchoolList> lists() {
        return v.schoolLists();
    }

    @PostMapping("/school-lists")
    @PreAuthorize("hasAuthority('PRODUCTOS_EDITAR')")
    public Map<String, Long> createList(@RequestBody VerticalsService.SchoolListRequest r) {
        return Map.of("id", v.saveSchoolList(null, r));
    }

    @PutMapping("/school-lists/{id}")
    @PreAuthorize("hasAuthority('PRODUCTOS_EDITAR')")
    public Map<String, Long> updateList(@PathVariable Long id, @RequestBody VerticalsService.SchoolListRequest r) {
        return Map.of("id", v.saveSchoolList(id, r));
    }

    @DeleteMapping("/school-lists/{id}")
    @PreAuthorize("hasAuthority('PRODUCTOS_EDITAR')")
    public Map<String, Boolean> deleteList(@PathVariable Long id) {
        v.deleteSchoolList(id);
        return Map.of("ok", true);
    }
}
