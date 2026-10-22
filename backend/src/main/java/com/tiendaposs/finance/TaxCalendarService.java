package com.tiendaposs.finance;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.finance.FinanceRepos.TaxCalendarItemRepository;
import com.tiendaposs.finance.FinanceRepos.TaxCalendarSettingsRepository;
import com.tiendaposs.fiscal.FiscalProfile;
import com.tiendaposs.fiscal.FiscalRepos.FiscalProfileRepository;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.tenant.TenantRepository;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Fase 15: calendario tributario como RECORDATORIO. No liquida ni presenta nada.
 * <p>
 * Las fechas exactas dependen del decreto anual de la DIAN (y del municipio para el ICA) y del último dígito del NIT;
 * por eso se generan como ESTIMADAS con la fecha más temprana probable (el día 10 del mes en que suele vencer), para que
 * el aviso llegue antes y no después. El dueño o su contador ponen la fecha exacta y desde ahí deja de ser estimada.
 * La única fecha fija es la renovación de la matrícula mercantil (31 de marzo).
 */
@Service
public class TaxCalendarService {

    private static final String[] MONTHS = {"Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic"};

    private final TaxCalendarSettingsRepository settingsRepo;
    private final TaxCalendarItemRepository items;
    private final FiscalProfileRepository profiles;
    private final TenantRepository tenants;
    private final AuditService audit;

    public TaxCalendarService(TaxCalendarSettingsRepository settingsRepo, TaxCalendarItemRepository items, FiscalProfileRepository profiles,
                              TenantRepository tenants, AuditService audit) {
        this.settingsRepo = settingsRepo;
        this.items = items;
        this.profiles = profiles;
        this.tenants = tenants;
        this.audit = audit;
    }

    // ---------------- DTOs ----------------

    public record SettingsDto(String ivaPeriod, boolean simpleRegime, boolean retentionAgent, String icaPeriod, String incomeTax,
                              boolean mercantileRegistry, int reminderDays, boolean configured,
                              /** Por qué se sugiere así (cuando todavía no se ha configurado). */ List<String> hints) {
    }

    public record SettingsRequest(@NotNull @Pattern(regexp = "NINGUNO|BIMESTRAL|CUATRIMESTRAL") String ivaPeriod, boolean simpleRegime,
                                  boolean retentionAgent, @NotNull @Pattern(regexp = "NINGUNO|BIMESTRAL|ANUAL") String icaPeriod,
                                  @NotNull @Pattern(regexp = "NINGUNO|NATURAL|JURIDICA") String incomeTax, boolean mercantileRegistry,
                                  @Min(value = 1, message = "mínimo 1 día") @Max(value = 30, message = "máximo 30 días") Integer reminderDays) {
    }

    /** status: PENDIENTE, PROXIMA (dentro de los días de aviso), VENCIDA o HECHA. */
    public record ItemDto(Long id, String code, String name, String periodLabel, LocalDate dueDate, boolean estimated, long daysLeft,
                          String status, Instant doneAt, String doneBy, String note, boolean custom) {
    }

    public record Calendar(SettingsDto settings, List<ItemDto> items) {
    }

    public record ItemUpdate(LocalDate dueDate, Boolean done, @Size(max = 300) String note) {
    }

    public record CustomRequest(@NotBlank(message = "escribe qué hay que hacer") @Size(max = 120) String name, @NotNull LocalDate dueDate,
                                @Size(max = 300) String note) {
    }

    // ---------------- Configuración ----------------

    @Transactional
    public Calendar calendar() {
        CurrentUser.require(Permission.FINANZAS_VER);
        Long t = TenantContext.require();
        TaxCalendarSettings s = settingsOf(t);
        if (s.configured) generate(s);
        return new Calendar(dto(s), list(t, s));
    }

    @Transactional
    public Calendar saveSettings(SettingsRequest r) {
        CurrentUser.require(Permission.FINANZAS_GASTOS);
        Long t = TenantContext.require();
        TaxCalendarSettings s = settingsOf(t);
        s.ivaPeriod = r.ivaPeriod();
        s.simpleRegime = r.simpleRegime();
        s.retentionAgent = r.retentionAgent();
        s.icaPeriod = r.icaPeriod();
        s.incomeTax = r.incomeTax();
        s.mercantileRegistry = r.mercantileRegistry();
        s.reminderDays = r.reminderDays() == null ? 7 : r.reminderDays();
        s.configured = true;
        s.updatedAt = Instant.now();
        settingsRepo.save(s);
        // Las fechas futuras sin hacer se rehacen (p. ej. si el IVA pasó de bimestral a cuatrimestral), salvo las que
        // alguien ya ajustó de una obligación que sigue activa. Lo hecho y lo pasado se conserva como historial.
        Set<String> enabled = enabledCodes(s);
        LocalDate today = today(t);
        for (TaxCalendarItem i : items.findByTenantIdOrderByDueDateAscIdAsc(t)) {
            if ("OTRA".equals(i.code) || i.doneAt != null || i.dueDate.isBefore(today)) continue;
            if (i.estimated || !enabled.contains(i.code)) items.delete(i);
        }
        items.flush();
        generate(s);
        audit.log("CALENDARIO_TRIBUTARIO", "TENANT", t, "Configuró el calendario tributario: " + String.join(", ", enabled));
        return new Calendar(dto(s), list(t, s));
    }

    // ---------------- Fechas ----------------

    @Transactional
    public ItemDto update(Long id, ItemUpdate r) {
        CurrentUser.require(Permission.FINANZAS_GASTOS);
        Long t = TenantContext.require();
        TaxCalendarItem i = items.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Fecha no encontrada"));
        if (r.dueDate() != null && !r.dueDate().equals(i.dueDate)) {
            i.dueDate = r.dueDate();
            i.estimated = false;
        } else if (r.dueDate() != null) {
            i.estimated = false; // confirmó que la estimada es la correcta
        }
        if (r.done() != null) {
            if (r.done() && i.doneAt == null) {
                i.doneAt = Instant.now();
                i.doneBy = CurrentUser.get().fullName();
            } else if (!r.done()) {
                i.doneAt = null;
                i.doneBy = null;
            }
        }
        if (r.note() != null) i.note = r.note().isBlank() ? null : r.note().trim();
        items.save(i);
        return dto(i, settingsOf(t), today(t));
    }

    @Transactional
    public ItemDto addCustom(CustomRequest r) {
        CurrentUser.require(Permission.FINANZAS_GASTOS);
        Long t = TenantContext.require();
        TaxCalendarItem i = new TaxCalendarItem();
        i.tenantId = t;
        i.code = "OTRA";
        i.name = r.name().trim();
        i.periodKey = "X" + UUID.randomUUID().toString().substring(0, 12);
        i.periodLabel = "Recordatorio propio";
        i.dueDate = r.dueDate();
        i.estimated = false;
        i.note = r.note() == null || r.note().isBlank() ? null : r.note().trim();
        items.save(i);
        return dto(i, settingsOf(t), today(t));
    }

    @Transactional
    public void deleteCustom(Long id) {
        CurrentUser.require(Permission.FINANZAS_GASTOS);
        TaxCalendarItem i = items.findByIdAndTenantId(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Fecha no encontrada"));
        if (!"OTRA".equals(i.code)) throw ApiException.badRequest("Las obligaciones del calendario se quitan desde la configuración");
        items.delete(i);
    }

    /** Pendientes que vencen dentro de los días de aviso o ya vencieron (para alertas y tablero). */
    @Transactional(readOnly = true)
    public List<ItemDto> upcoming(Long tenantId, int max) {
        TaxCalendarSettings s = settingsRepo.findById(tenantId).orElse(null);
        if (s == null || !s.configured) return List.of();
        return list(tenantId, s).stream().filter(i -> "PROXIMA".equals(i.status()) || "VENCIDA".equals(i.status())).limit(max).toList();
    }

    // ---------------- Generación ----------------

    /** Crea las fechas de los próximos 12 meses que falten (no toca las que ya existen). */
    private void generate(TaxCalendarSettings s) {
        Long t = s.tenantId;
        // Desde hoy: lo que ya pasó antes de configurar no se inventa como "vencido".
        LocalDate from = today(t);
        LocalDate to = from.withDayOfMonth(1).plusMonths(12);
        int y0 = from.getYear() - 1;
        for (int y = y0; y <= to.getYear(); y++) {
            if ("BIMESTRAL".equals(s.ivaPeriod)) bimonthly(t, "IVA", "Declaración de IVA (bimestral)", y, from, to);
            if ("CUATRIMESTRAL".equals(s.ivaPeriod)) {
                for (int c = 1; c <= 3; c++) {
                    int startMonth = (c - 1) * 4 + 1;
                    LocalDate due = YearMonth.of(y, startMonth).plusMonths(4).atDay(10);
                    add(t, "IVA", "Declaración de IVA (cuatrimestral)", y + "-C" + c,
                            MONTHS[startMonth - 1] + "–" + MONTHS[startMonth + 2] + " " + y, due, true, from, to);
                }
            }
            if (s.retentionAgent) {
                for (int m = 1; m <= 12; m++) {
                    add(t, "RETENCION", "Retención en la fuente (mensual)", y + "-" + String.format("%02d", m), MONTHS[m - 1] + " " + y,
                            YearMonth.of(y, m).plusMonths(1).atDay(10), true, from, to);
                }
            }
            if (s.simpleRegime) {
                bimonthly(t, "SIMPLE_ANTICIPO", "Anticipo del régimen simple (bimestral)", y, from, to);
                add(t, "SIMPLE_ANUAL", "Declaración anual del régimen simple", String.valueOf(y), "Año " + y, LocalDate.of(y + 1, 4, 10), true, from, to);
            }
            if ("BIMESTRAL".equals(s.icaPeriod)) bimonthly(t, "ICA", "Declaración de ICA (bimestral)", y, from, to);
            if ("ANUAL".equals(s.icaPeriod)) {
                add(t, "ICA", "Declaración de ICA (anual)", String.valueOf(y), "Año " + y, LocalDate.of(y + 1, 2, 10), true, from, to);
            }
            if ("JURIDICA".equals(s.incomeTax)) {
                add(t, "RENTA", "Declaración de renta (persona jurídica)", String.valueOf(y), "Año " + y, LocalDate.of(y + 1, 5, 10), true, from, to);
            }
            if ("NATURAL".equals(s.incomeTax)) {
                add(t, "RENTA", "Declaración de renta (persona natural)", String.valueOf(y), "Año " + y, LocalDate.of(y + 1, 8, 10), true, from, to);
            }
            if (s.mercantileRegistry) {
                add(t, "MATRICULA", "Renovación de la matrícula mercantil", String.valueOf(y), "Año " + y, LocalDate.of(y, 3, 31), false, from, to);
            }
        }
    }

    private void bimonthly(Long t, String code, String name, int y, LocalDate from, LocalDate to) {
        for (int b = 1; b <= 6; b++) {
            int startMonth = (b - 1) * 2 + 1;
            add(t, code, name, y + "-B" + b, MONTHS[startMonth - 1] + "–" + MONTHS[startMonth] + " " + y,
                    YearMonth.of(y, startMonth).plusMonths(2).atDay(10), true, from, to);
        }
    }

    private void add(Long t, String code, String name, String key, String label, LocalDate due, boolean estimated, LocalDate from, LocalDate to) {
        if (due.isBefore(from) || !due.isBefore(to)) return;
        if (items.existsByTenantIdAndCodeAndPeriodKey(t, code, key)) return;
        TaxCalendarItem i = new TaxCalendarItem();
        i.tenantId = t;
        i.code = code;
        i.name = name;
        i.periodKey = key;
        i.periodLabel = label;
        i.dueDate = due;
        i.estimated = estimated;
        items.save(i);
    }

    // ---------------- auxiliares ----------------

    private List<ItemDto> list(Long t, TaxCalendarSettings s) {
        LocalDate today = today(t);
        LocalDate cutoff = today.withDayOfMonth(1).minusMonths(2);
        return items.findByTenantIdOrderByDueDateAscIdAsc(t).stream()
                // Lo hecho hace más de dos meses ya no estorba; lo vencido sin hacer se sigue mostrando.
                .filter(i -> !i.dueDate.isBefore(cutoff) || i.doneAt == null)
                .map(i -> dto(i, s, today)).toList();
    }

    private ItemDto dto(TaxCalendarItem i, TaxCalendarSettings s, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, i.dueDate);
        String status = i.doneAt != null ? "HECHA" : days < 0 ? "VENCIDA" : days <= s.reminderDays ? "PROXIMA" : "PENDIENTE";
        return new ItemDto(i.id, i.code, i.name, i.periodLabel, i.dueDate, i.estimated, days, status, i.doneAt, i.doneBy, i.note, "OTRA".equals(i.code));
    }

    private TaxCalendarSettings settingsOf(Long t) {
        return settingsRepo.findById(t).orElseGet(() -> {
            TaxCalendarSettings s = new TaxCalendarSettings();
            s.tenantId = t;
            suggest(s, profiles.findById(t).orElse(null));
            return s;
        });
    }

    /** Punto de partida según los datos fiscales; el dueño o el contador lo confirman. */
    private static void suggest(TaxCalendarSettings s, FiscalProfile p) {
        if (p == null) return;
        boolean simple = p.responsibilities != null && p.responsibilities.contains("O-47");
        s.simpleRegime = simple;
        s.ivaPeriod = Boolean.TRUE.equals(p.ivaResponsible) && !simple ? "CUATRIMESTRAL" : "NINGUNO";
        s.retentionAgent = "JURIDICA".equals(p.personType) && !simple;
        s.incomeTax = simple ? "NINGUNO" : "JURIDICA".equals(p.personType) ? "JURIDICA" : "NATURAL".equals(p.personType) ? "NATURAL" : "NINGUNO";
    }

    private SettingsDto dto(TaxCalendarSettings s) {
        List<String> hints = new ArrayList<>();
        if (!s.configured) {
            FiscalProfile p = profiles.findById(s.tenantId).orElse(null);
            if (p == null) hints.add("Completa los datos fiscales en Facturación para sugerirte las obligaciones.");
            else {
                hints.add("Sugerido con tus datos fiscales: revísalo con tu contador antes de guardar.");
                if ("CUATRIMESTRAL".equals(s.ivaPeriod)) hints.add("IVA cuatrimestral es lo usual con ingresos menores a 92.000 UVT; si son mayores, es bimestral.");
            }
        }
        return new SettingsDto(s.ivaPeriod, s.simpleRegime, s.retentionAgent, s.icaPeriod, s.incomeTax, s.mercantileRegistry, s.reminderDays,
                s.configured, hints);
    }

    private static Set<String> enabledCodes(TaxCalendarSettings s) {
        Set<String> out = new LinkedHashSet<>();
        if (!"NINGUNO".equals(s.ivaPeriod)) out.add("IVA");
        if (s.retentionAgent) out.add("RETENCION");
        if (s.simpleRegime) {
            out.add("SIMPLE_ANTICIPO");
            out.add("SIMPLE_ANUAL");
        }
        if (!"NINGUNO".equals(s.icaPeriod)) out.add("ICA");
        if (!"NINGUNO".equals(s.incomeTax)) out.add("RENTA");
        if (s.mercantileRegistry) out.add("MATRICULA");
        return out;
    }

    private LocalDate today(Long t) {
        return LocalDate.now(ZoneId.of(tenants.findById(t).map(Tenant::getTimezone).orElse("America/Bogota")));
    }
}
