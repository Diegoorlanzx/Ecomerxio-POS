package com.tiendaposs.verticals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogRepos.PresentationRepository;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Presentation;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.settings.ModulesService;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.*;

/**
 * Tipos de negocio, fase 5: veterinaria (mascotas y vacunas), óptica (fórmula del paciente), papelería (listas de útiles),
 * agro (registro ICA) y licorera/tienda (productos solo para mayores de edad). Cada parte exige su módulo.
 */
@Service
public class VerticalsService {

    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    private final ProductRepository products;
    private final PresentationRepository presentations;
    private final AuditService audit;
    private final ObjectMapper json;

    public VerticalsService(JdbcTemplate jdbc, SettingsService settings, ProductRepository products, PresentationRepository presentations,
                            AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.products = products;
        this.presentations = presentations;
        this.audit = audit;
        this.json = json;
    }

    private Long tenant(String module) {
        Long t = TenantContext.require();
        ModulesService.check(settings.forTenant(t), module);
        return t;
    }

    private static String clean(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static String required(String s, int max, String field, String msg) {
        String v = clean(s, max);
        if (v == null) throw ApiException.field(field, msg);
        return v;
    }

    private Long insert(String sql, Object... args) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, kh);
        return Objects.requireNonNull(kh.getKey()).longValue();
    }

    // =================================================================== Producto: ICA y mayores de edad

    public record ProductExtras(String icaCode, boolean adultsOnly) {
    }

    @Transactional
    public ProductExtras updateProduct(Long productId, ProductExtras r) {
        Long t = TenantContext.require();
        var st = settings.forTenant(t);
        if (!st.hasModule("AGRO") && !st.hasModule("MAYORES_EDAD")) {
            throw ApiException.conflict("Activa el módulo Agro o Mayores de edad en Más → Tipo de negocio y módulos.");
        }
        Product p = products.findByIdAndTenantId(productId, t).orElseThrow(() -> ApiException.notFound("Producto no encontrado"));
        if (st.hasModule("AGRO")) p.icaCode = clean(r.icaCode(), 40);
        if (st.hasModule("MAYORES_EDAD")) p.adultsOnly = r.adultsOnly();
        products.save(p);
        return new ProductExtras(p.icaCode, p.adultsOnly);
    }

    // =================================================================== Veterinaria

    public record PetRequest(Long customerId, String ownerName, String ownerPhone, String name, String species, String breed, String sex,
                             LocalDate birthDate, String notes) {
    }

    public record Vaccine(Long id, String vaccine, LocalDate appliedOn, LocalDate nextDue, String notes) {
    }

    public record Pet(Long id, Long customerId, String ownerName, String ownerPhone, String name, String species, String breed, String sex,
                      LocalDate birthDate, String notes, List<Vaccine> vaccines, LocalDate nextDue) {
    }

    public record VaccineRequest(String vaccine, LocalDate appliedOn, LocalDate nextDue, String notes) {
    }

    public record DueVaccine(Long petId, String petName, String species, String ownerName, String ownerPhone, String vaccine, LocalDate nextDue) {
    }

    public List<Pet> pets(String q) {
        Long t = tenant("MASCOTAS");
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        List<Pet> base = jdbc.query("""
                SELECT p.id, p.customer_id, coalesce(c.name, p.owner_name), coalesce(c.phone, p.owner_phone), p.name, p.species, p.breed, p.sex,
                       p.birth_date, p.notes
                FROM pets p LEFT JOIN customers c ON c.id = p.customer_id
                WHERE p.tenant_id = ? AND (lower(p.name) LIKE ? OR lower(coalesce(c.name, p.owner_name, '')) LIKE ? OR coalesce(c.phone, p.owner_phone, '') LIKE ?)
                ORDER BY p.name LIMIT 200""",
                (rs, i) -> new Pet(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getDate(9) == null ? null : rs.getDate(9).toLocalDate(), rs.getString(10), List.of(), null),
                t, like, like, like);
        List<Pet> out = new ArrayList<>();
        for (Pet p : base) {
            List<Vaccine> vs = vaccines(p.id());
            LocalDate next = vs.stream().map(Vaccine::nextDue).filter(Objects::nonNull).filter(d -> !d.isBefore(LocalDate.now().minusDays(60)))
                    .min(Comparator.naturalOrder()).orElse(null);
            out.add(new Pet(p.id(), p.customerId(), p.ownerName(), p.ownerPhone(), p.name(), p.species(), p.breed(), p.sex(), p.birthDate(),
                    p.notes(), vs, next));
        }
        return out;
    }

    private List<Vaccine> vaccines(Long petId) {
        return jdbc.query("SELECT id, vaccine, applied_on, next_due, notes FROM pet_vaccines WHERE pet_id = ? ORDER BY applied_on DESC, id DESC",
                (rs, i) -> new Vaccine(rs.getLong(1), rs.getString(2), rs.getDate(3).toLocalDate(),
                        rs.getDate(4) == null ? null : rs.getDate(4).toLocalDate(), rs.getString(5)), petId);
    }

    @Transactional
    public Long createPet(PetRequest r) {
        Long t = tenant("MASCOTAS");
        if (r.customerId() != null) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM customers WHERE id = ? AND tenant_id = ?", Integer.class, r.customerId(), t);
            if (n == null || n == 0) throw ApiException.field("customerId", "cliente no encontrado");
        } else if (clean(r.ownerName(), 120) == null) {
            throw ApiException.field("ownerName", "escribe el nombre del dueño o elige un cliente");
        }
        if (r.birthDate() != null && r.birthDate().isAfter(LocalDate.now())) throw ApiException.field("birthDate", "no puede ser futura");
        Long id = insert("INSERT INTO pets (tenant_id, customer_id, owner_name, owner_phone, name, species, breed, sex, birth_date, notes) VALUES (?,?,?,?,?,?,?,?,?,?)",
                t, r.customerId(), clean(r.ownerName(), 120), clean(r.ownerPhone(), 30), required(r.name(), 60, "name", "escribe el nombre"),
                required(r.species(), 30, "species", "elige la especie"), clean(r.breed(), 60), clean(r.sex(), 10),
                r.birthDate() == null ? null : Date.valueOf(r.birthDate()), clean(r.notes(), 500));
        audit.log("MASCOTA_CREADA", "PET", id, "Registró la mascota " + r.name());
        return id;
    }

    @Transactional
    public void addVaccine(Long petId, VaccineRequest r) {
        Long t = tenant("MASCOTAS");
        Integer n = jdbc.queryForObject("SELECT count(*) FROM pets WHERE id = ? AND tenant_id = ?", Integer.class, petId, t);
        if (n == null || n == 0) throw ApiException.notFound("Mascota no encontrada");
        LocalDate applied = r.appliedOn() == null ? LocalDate.now() : r.appliedOn();
        if (applied.isAfter(LocalDate.now())) throw ApiException.field("appliedOn", "no puede ser futura");
        if (r.nextDue() != null && !r.nextDue().isAfter(applied)) throw ApiException.field("nextDue", "debe ser después de la aplicación");
        jdbc.update("INSERT INTO pet_vaccines (tenant_id, pet_id, vaccine, applied_on, next_due, notes) VALUES (?,?,?,?,?,?)",
                t, petId, required(r.vaccine(), 80, "vaccine", "escribe la vacuna"), Date.valueOf(applied),
                r.nextDue() == null ? null : Date.valueOf(r.nextDue()), clean(r.notes(), 300));
    }

    /** Vacunas que tocan en los próximos días (o ya vencidas hace poco): para recordarle al dueño por WhatsApp. */
    public List<DueVaccine> dueVaccines(int days) {
        Long t = tenant("MASCOTAS");
        return jdbc.query("""
                SELECT p.id, p.name, p.species, coalesce(c.name, p.owner_name), coalesce(c.phone, p.owner_phone), v.vaccine, v.next_due
                FROM pet_vaccines v JOIN pets p ON p.id = v.pet_id LEFT JOIN customers c ON c.id = p.customer_id
                WHERE v.tenant_id = ? AND v.next_due BETWEEN ? AND ?
                  AND NOT EXISTS (SELECT 1 FROM pet_vaccines n WHERE n.pet_id = v.pet_id AND n.vaccine = v.vaccine AND n.applied_on > v.applied_on)
                ORDER BY v.next_due""",
                (rs, i) -> new DueVaccine(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getDate(7).toLocalDate()),
                t, Date.valueOf(LocalDate.now().minusDays(30)), Date.valueOf(LocalDate.now().plusDays(Math.max(1, Math.min(days, 90)))));
    }

    // =================================================================== Óptica

    public record OpticalRequest(Long customerId, String patientName, String patientDoc, String patientPhone, LocalDate examDate, String optometrist,
                                 BigDecimal odSphere, BigDecimal odCylinder, Integer odAxis, BigDecimal oiSphere, BigDecimal oiCylinder,
                                 Integer oiAxis, BigDecimal addition, BigDecimal pupillaryDistance, String lensType, String notes) {
    }

    public record Optical(Long id, Long customerId, String patientName, String patientDoc, String patientPhone, LocalDate examDate,
                          String optometrist, BigDecimal odSphere, BigDecimal odCylinder, Integer odAxis, BigDecimal oiSphere,
                          BigDecimal oiCylinder, Integer oiAxis, BigDecimal addition, BigDecimal pupillaryDistance, String lensType, String notes) {
    }

    public List<Optical> opticals(String q) {
        Long t = tenant("OPTICA");
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return jdbc.query("""
                SELECT id, customer_id, patient_name, patient_doc, patient_phone, exam_date, optometrist, od_sphere, od_cylinder, od_axis,
                       oi_sphere, oi_cylinder, oi_axis, addition, pupillary_distance, lens_type, notes
                FROM optical_prescriptions WHERE tenant_id = ? AND (lower(patient_name) LIKE ? OR coalesce(patient_doc, '') LIKE ?)
                ORDER BY exam_date DESC, id DESC LIMIT 200""",
                (rs, i) -> new Optical(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getDate(6).toLocalDate(), rs.getString(7), rs.getBigDecimal(8), rs.getBigDecimal(9), (Integer) rs.getObject(10),
                        rs.getBigDecimal(11), rs.getBigDecimal(12), (Integer) rs.getObject(13), rs.getBigDecimal(14), rs.getBigDecimal(15),
                        rs.getString(16), rs.getString(17)),
                t, like, like);
    }

    @Transactional
    public Long createOptical(OpticalRequest r) {
        Long t = tenant("OPTICA");
        checkLens("odSphere", r.odSphere(), 30);
        checkLens("oiSphere", r.oiSphere(), 30);
        checkLens("odCylinder", r.odCylinder(), 10);
        checkLens("oiCylinder", r.oiCylinder(), 10);
        checkAxis("odAxis", r.odAxis());
        checkAxis("oiAxis", r.oiAxis());
        if (r.addition() != null && (r.addition().signum() < 0 || r.addition().compareTo(BigDecimal.valueOf(4)) > 0)) throw ApiException.field("addition", "entre 0 y 4");
        LocalDate exam = r.examDate() == null ? LocalDate.now() : r.examDate();
        if (exam.isAfter(LocalDate.now())) throw ApiException.field("examDate", "no puede ser futura");
        return insert("""
                INSERT INTO optical_prescriptions (tenant_id, customer_id, patient_name, patient_doc, patient_phone, exam_date, optometrist,
                    od_sphere, od_cylinder, od_axis, oi_sphere, oi_cylinder, oi_axis, addition, pupillary_distance, lens_type, notes)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                t, r.customerId(), required(r.patientName(), 120, "patientName", "escribe el paciente"), clean(r.patientDoc(), 30),
                clean(r.patientPhone(), 30), Date.valueOf(exam), clean(r.optometrist(), 120), r.odSphere(), r.odCylinder(), r.odAxis(),
                r.oiSphere(), r.oiCylinder(), r.oiAxis(), r.addition(), r.pupillaryDistance(), clean(r.lensType(), 60), clean(r.notes(), 500));
    }

    private static void checkLens(String field, BigDecimal v, int max) {
        if (v != null && v.abs().compareTo(BigDecimal.valueOf(max)) > 0) throw ApiException.field(field, "valor fuera de rango");
    }

    private static void checkAxis(String field, Integer v) {
        if (v != null && (v < 0 || v > 180)) throw ApiException.field(field, "entre 0 y 180");
    }

    // =================================================================== Papelería: listas de útiles

    public record ListItem(Long presentationId, BigDecimal quantity, String note) {
    }

    public record SchoolListRequest(String school, String grade, Integer year, List<ListItem> items) {
    }

    public record SchoolListItem(Long presentationId, String productName, String presentationName, BigDecimal price, BigDecimal quantity, String note) {
    }

    public record SchoolList(Long id, String school, String grade, int year, List<SchoolListItem> items, BigDecimal total) {
    }

    public List<SchoolList> schoolLists() {
        Long t = tenant("LISTAS_UTILES");
        return jdbc.query("SELECT id, school, grade, year, items FROM school_lists WHERE tenant_id = ? AND active ORDER BY school, grade",
                (rs, i) -> toList(t, rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)), t);
    }

    private SchoolList toList(Long t, Long id, String school, String grade, int year, String itemsJson) {
        List<ListItem> raw;
        try {
            raw = json.readValue(itemsJson, new TypeReference<>() {
            });
        } catch (Exception e) {
            raw = List.of();
        }
        List<SchoolListItem> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ListItem it : raw) {
            Presentation pr = presentations.findByIdAndTenantId(it.presentationId(), t).orElse(null);
            if (pr == null) continue;
            Product p = products.findByIdAndTenantId(pr.productId, t).orElse(null);
            if (p == null) continue;
            items.add(new SchoolListItem(pr.id, p.name, pr.name, pr.price, it.quantity(), it.note()));
            total = total.add(pr.price.multiply(it.quantity()));
        }
        return new SchoolList(id, school, grade, year, items, total);
    }

    @Transactional
    public Long saveSchoolList(Long id, SchoolListRequest r) {
        Long t = tenant("LISTAS_UTILES");
        if (r.items() == null || r.items().isEmpty()) throw ApiException.field("items", "agrega al menos un útil");
        for (ListItem it : r.items()) {
            if (it.quantity() == null || it.quantity().signum() <= 0) throw ApiException.field("items", "las cantidades deben ser mayores que cero");
            if (presentations.findByIdAndTenantId(it.presentationId(), t).isEmpty()) throw ApiException.field("items", "un producto de la lista no existe");
        }
        String items;
        try {
            items = json.writeValueAsString(r.items());
        } catch (Exception e) {
            throw ApiException.badRequest("Lista no válida");
        }
        int year = r.year() == null ? LocalDate.now().getYear() : r.year();
        String school = required(r.school(), 120, "school", "escribe el colegio");
        String grade = required(r.grade(), 40, "grade", "escribe el grado");
        if (id == null) {
            return insert("INSERT INTO school_lists (tenant_id, school, grade, year, items) VALUES (?,?,?,?,?)", t, school, grade, year, items);
        }
        if (jdbc.update("UPDATE school_lists SET school = ?, grade = ?, year = ?, items = ? WHERE id = ? AND tenant_id = ?",
                school, grade, year, items, id, t) == 0) {
            throw ApiException.notFound("Lista no encontrada");
        }
        return id;
    }

    @Transactional
    public void deleteSchoolList(Long id) {
        Long t = tenant("LISTAS_UTILES");
        if (jdbc.update("UPDATE school_lists SET active = FALSE WHERE id = ? AND tenant_id = ?", id, t) == 0) throw ApiException.notFound("Lista no encontrada");
    }
}
