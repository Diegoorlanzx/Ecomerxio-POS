package com.tiendaposs.analytics;

import com.tiendaposs.common.ApiException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@RestController
@RequestMapping("/api")
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final DashboardService dashboard;

    public AnalyticsController(AnalyticsService analytics, DashboardService dashboard) {
        this.analytics = analytics;
        this.dashboard = dashboard;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('DASHBOARD_VER')")
    public DashboardService.Dashboard dashboard() {
        return dashboard.dashboard();
    }

    @GetMapping("/analytics/report")
    @PreAuthorize("hasAuthority('REPORTES_VER')")
    public AnalyticsService.Report report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] r = range(from, to);
        return analytics.report(r[0], r[1]);
    }

    @GetMapping("/analytics/profitability")
    @PreAuthorize("hasAuthority('COSTOS_VER')")
    public AnalyticsService.Profitability profitability(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] r = range(from, to);
        return analytics.profitability(r[0], r[1]);
    }

    @GetMapping("/analytics/products/{id}/evolution")
    @PreAuthorize("hasAuthority('COSTOS_VER')")
    public AnalyticsService.Evolution evolution(@PathVariable Long id) {
        return analytics.evolution(id);
    }

    @GetMapping("/analytics/export")
    @PreAuthorize("hasAuthority('REPORTES_EXPORTAR')")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "ventas") String type,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] r = range(from, to);
        String kind = "rentabilidad".equals(type) ? "rentabilidad" : "ventas";
        byte[] body = analytics.exportCsv(kind, r[0], r[1]).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + kind + "_" + r[0] + "_" + r[1] + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(body);
    }

    private LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate t = to == null ? analytics.today() : to;
        LocalDate f = from == null ? t.withDayOfMonth(1) : from;
        if (f.isAfter(t)) throw ApiException.field("from", "la fecha inicial es posterior a la final");
        if (ChronoUnit.DAYS.between(f, t) > 366) throw ApiException.field("from", "el rango máximo es de un año");
        return new LocalDate[]{f, t};
    }
}
