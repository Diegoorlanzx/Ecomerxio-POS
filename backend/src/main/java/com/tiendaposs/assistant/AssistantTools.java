package com.tiendaposs.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.analytics.AnalyticsService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Money;
import com.tiendaposs.notifications.NotificationService;
import com.tiendaposs.purchasing.InsightsService;
import com.tiendaposs.purchasing.SupplierService;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Consultas que el asistente puede hacer sobre la tienda. Cada una exige los mismos permisos que la
 * pantalla equivalente: el asistente nunca ve más de lo que la persona que pregunta puede ver.
 * Todas devuelven un "resumen" en texto (lo que se muestra en el modo de consultas rápidas) y datos.
 */
@Component
public class AssistantTools {

    public record Param(String name, String type, String description, List<String> enumValues, boolean required) {
    }

    public record ToolDef(String name, String title, String description, List<Param> params, List<String> anyPermission,
                          Function<JsonNode, Map<String, Object>> run) {
        public boolean allowed(AuthUser u) {
            return anyPermission.isEmpty() || anyPermission.stream().anyMatch(p -> u.permissions().contains(p));
        }
    }

    private final AnalyticsService analytics;
    private final InsightsService insights;
    private final SupplierService suppliers;
    private final NotificationService notifications;
    private final RecommendationService recommendations;
    private final EntityManager em;
    private final Map<String, ToolDef> tools = new LinkedHashMap<>();

    public AssistantTools(AnalyticsService analytics, InsightsService insights, SupplierService suppliers, NotificationService notifications,
                          RecommendationService recommendations, EntityManager em) {
        this.analytics = analytics;
        this.insights = insights;
        this.suppliers = suppliers;
        this.notifications = notifications;
        this.recommendations = recommendations;
        this.em = em;
        Param desde = new Param("desde", "string", "Fecha inicial AAAA-MM-DD (por defecto, inicio del mes)", null, false);
        Param hasta = new Param("hasta", "string", "Fecha final AAAA-MM-DD (por defecto, hoy)", null, false);
        add(new ToolDef("ventas_periodo", "¿Cuánto vendí?", "Ventas de un período: total, transacciones, ticket promedio, unidades, utilidad bruta "
                + "(si el usuario ve costos), comparación con el período anterior equivalente, medios de pago y productos más vendidos.",
                List.of(desde, hasta), List.of("REPORTES_VER"), this::sales));
        add(new ToolDef("productos_rentabilidad", "¿Qué deja más utilidad?", "Rentabilidad por producto en un período: utilidad bruta, margen, utilidad por unidad, "
                + "descuentos y clasificación (estrella, vende mucho y deja poco, nicho rentable, para revisar).",
                List.of(desde, hasta, new Param("orden", "string", "Cómo ordenar", List.of("mayor_utilidad", "menor_margen", "mas_vendidos"), false),
                        new Param("limite", "integer", "Cuántos productos (máximo 30)", null, false)),
                List.of("COSTOS_VER"), this::profitability));
        add(new ToolDef("sugerencias_compra", "¿Qué compro esta semana?", "Pedido sugerido por la reposición automática: qué pedir a cada proveedor, cuántas unidades, "
                + "por qué (demanda diaria, cobertura, punto de pedido), prioridad, costo estimado, dinero total necesario para reponer y presupuesto disponible.",
                List.of(new Param("proveedor", "string", "Filtrar por nombre de proveedor (opcional)", null, false)),
                List.of("COMPRAS_VER", "INVENTARIO_VER"), this::purchases));
        add(new ToolDef("productos_en_caida", "¿Qué se vende menos?", "Productos cuyas ventas bajaron frente al período anterior equivalente.",
                List.of(desde, hasta), List.of("REPORTES_VER"), this::declining));
        add(new ToolDef("comparar_proveedores", "Comparar proveedores", "Compara proveedores: cumplimiento de entregas, tiempos, pedidos, saldo por pagar y, si se "
                + "indica un producto, el costo por unidad que ofrece cada uno.",
                List.of(new Param("producto", "string", "Nombre del producto para comparar precios (opcional)", null, false)),
                List.of("PROVEEDORES_VER", "COMPRAS_VER"), this::compareSuppliers));
        add(new ToolDef("resumen_periodo", "Resumen semanal o mensual", "Resumen ejecutivo de una semana o un mes (actual o anterior): ventas, comparación, "
                + "utilidad, más vendidos, medios de pago, diferencias de caja y anulaciones.",
                List.of(new Param("tipo", "string", "Semana o mes", List.of("semana", "mes"), true),
                        new Param("anterior", "boolean", "true = la semana o el mes anterior completo", null, false)),
                List.of("REPORTES_VER"), this::summary));
        add(new ToolDef("detectar_anomalias", "Detectar anomalías", "Busca situaciones para revisar en los últimos días: diferencias de caja, muchas anulaciones o "
                + "devoluciones por empleado, ventas por debajo del costo, descuentos altos, referencias de pago repetidas y ajustes de inventario grandes.",
                List.of(new Param("dias", "integer", "Cuántos días hacia atrás (por defecto 7, máximo 60)", null, false)),
                List.of("REPORTES_VER", "AUDITORIA_VER", "CAJA_SUPERVISAR"), this::anomalies));
        add(new ToolDef("proponer_precios_promociones", "Recomendar precios y promociones", "Calcula y guarda recomendaciones de precio (productos con margen bajo) y "
                + "de promoción (productos quietos o con exceso) para que una persona las revise y apruebe en la pantalla de recomendaciones. No cambia nada por sí sola.",
                List.of(), List.of("COSTOS_VER"), this::propose));
        add(new ToolDef("estado_inventario", "Estado del inventario", "Existencias, cobertura en días, ventas diarias y alertas de los productos; permite buscar por nombre.",
                List.of(new Param("buscar", "string", "Texto para buscar productos (opcional)", null, false),
                        new Param("filtro", "string", "Solo un grupo", List.of("todos", "agotados", "por_agotarse", "sin_rotacion", "exceso"), false)),
                List.of("INVENTARIO_VER", "PRODUCTOS_VER"), this::inventory));
    }

    private void add(ToolDef t) {
        tools.put(t.name(), t);
    }

    public List<ToolDef> all() {
        return List.copyOf(tools.values());
    }

    public List<ToolDef> allowedFor(AuthUser u) {
        return tools.values().stream().filter(t -> t.allowed(u)).toList();
    }

    /** Ejecuta una consulta con los permisos del usuario actual. */
    public Map<String, Object> run(String name, JsonNode input) {
        ToolDef t = tools.get(name);
        if (t == null) throw ApiException.badRequest("Consulta desconocida: " + name);
        if (!t.allowed(CurrentUser.get())) throw ApiException.forbidden("No tienes permiso para esta consulta");
        return t.run().apply(input == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : input);
    }

    // =====================================================================
    // Consultas
    // =====================================================================

    private Map<String, Object> sales(JsonNode in) {
        LocalDate[] r = range(in);
        AnalyticsService.Report rep = analytics.report(r[0], r[1]);
        AnalyticsService.Kpis k = rep.current(), p = rep.previous();
        StringBuilder sb = new StringBuilder();
        sb.append("Del ").append(r[0]).append(" al ").append(r[1]).append(": ventas ").append(Money.fmt(k.sales())).append(" en ").append(k.transactions())
                .append(" transacciones (ticket promedio ").append(Money.fmt(k.avgTicket())).append(", ").append(plain(k.units())).append(" unidades).");
        if (k.grossProfit() != null) sb.append(" Utilidad bruta estimada ").append(Money.fmt(k.grossProfit())).append(" (margen ").append(plain(k.marginPct())).append("%).");
        sb.append(" Período anterior (").append(rep.prevFrom()).append(" a ").append(rep.prevTo()).append("): ").append(Money.fmt(p.sales()));
        BigDecimal ch = rep.changes().get("sales");
        if (ch != null) sb.append(" → ").append(ch.signum() >= 0 ? "+" : "").append(plain(ch)).append("%");
        sb.append('.');
        if (!rep.methods().isEmpty()) sb.append(" Medios: ").append(rep.methods().stream().map(m -> m.key().toLowerCase() + " " + Money.fmt(m.sales()))
                .collect(Collectors.joining(", "))).append('.');
        List<AnalyticsService.ProductRow> top = rep.products().stream().sorted(Comparator.comparing(AnalyticsService.ProductRow::units).reversed()).limit(5).toList();
        if (!top.isEmpty()) sb.append(" Más vendidos: ").append(top.stream().map(x -> x.name() + " (" + plain(x.units()) + ")").collect(Collectors.joining(", "))).append('.');
        if (rep.bestWeekday() != null) sb.append(" Mejor día: ").append(rep.bestWeekday()).append(rep.bestHour() == null ? "" : ", hora pico " + rep.bestHour() + ":00").append('.');
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("desde", r[0]);
        data.put("hasta", r[1]);
        data.put("actual", k);
        data.put("anterior", p);
        data.put("cambios_pct", rep.changes());
        data.put("por_medio_de_pago", rep.methods());
        data.put("mas_vendidos", top);
        data.put("por_categoria", rep.categories());
        data.put("por_empleado", rep.employees());
        return result(sb.toString(), data);
    }

    private Map<String, Object> profitability(JsonNode in) {
        LocalDate[] r = range(in);
        AnalyticsService.Profitability p = analytics.profitability(r[0], r[1]);
        String order = in.path("orden").asText("mayor_utilidad");
        int limit = Math.max(1, Math.min(30, in.path("limite").asInt(10)));
        Comparator<AnalyticsService.ProfitRow> cmp = switch (order) {
            case "menor_margen" -> Comparator.comparing(AnalyticsService.ProfitRow::marginPct);
            case "mas_vendidos" -> Comparator.comparing(AnalyticsService.ProfitRow::units).reversed();
            default -> Comparator.comparing(AnalyticsService.ProfitRow::grossProfit).reversed();
        };
        List<AnalyticsService.ProfitRow> rows = p.products().stream().sorted(cmp).limit(limit).toList();
        StringBuilder sb = new StringBuilder("Del ").append(r[0]).append(" al ").append(r[1]).append(": utilidad bruta ").append(Money.fmt(p.grossProfit()))
                .append(" (margen ").append(plain(p.marginPct())).append("%), gastos ").append(Money.fmt(p.expenses())).append(", utilidad neta estimada ")
                .append(Money.fmt(p.netProfit())).append(". ");
        sb.append(switch (order) {
            case "menor_margen" -> "Menor margen: ";
            case "mas_vendidos" -> "Más vendidos: ";
            default -> "Mayor utilidad: ";
        });
        sb.append(rows.stream().map(x -> x.name() + " " + Money.fmt(x.grossProfit()) + " (" + plain(x.marginPct()) + "%)").collect(Collectors.joining("; "))).append('.');
        return result(sb.toString(), Map.of("totales", Map.of("ingreso_neto", p.revenueEx(), "costo", p.cost(), "utilidad_bruta", p.grossProfit(),
                "margen_pct", p.marginPct(), "gastos", p.expenses(), "utilidad_neta", p.netProfit(), "descuentos", p.discounts(),
                "margen_sin_descuentos_pct", p.marginWithoutDiscountsPct()), "productos", rows, "clasificacion", p.quadrantCounts(),
                "umbral_margen_bajo", p.lowMarginPct(), "umbral_margen_alto", p.highMarginPct()));
    }

    private Map<String, Object> purchases(JsonNode in) {
        InsightsService.Suggestions s = insights.suggestions();
        String filter = norm(in.path("proveedor").asText(""));
        List<InsightsService.SupplierSuggestion> groups = s.suppliers().stream()
                .filter(g -> !g.lines().isEmpty() && (filter.isEmpty() || norm(g.supplierName()).contains(filter))).toList();
        StringBuilder sb = new StringBuilder();
        if (groups.isEmpty() && s.withoutSupplier().isEmpty()) {
            sb.append("Por ahora no hace falta pedir nada: las existencias alcanzan según el ritmo de ventas.");
        } else {
            sb.append("Pedido sugerido").append(s.suggestedTotal() == null ? "" : " por " + Money.fmt(s.suggestedTotal()))
                    .append(s.criticalTotal() == null || s.criticalTotal().signum() == 0 ? "" : " (lo crítico: " + Money.fmt(s.criticalTotal()) + ")").append(". ");
            for (InsightsService.SupplierSuggestion g : groups) {
                sb.append(g.supplierName()).append(g.estimatedTotal() == null ? "" : " " + Money.fmt(g.estimatedTotal()))
                        .append(g.nextOrderDate() == null ? "" : ", pedir el " + g.nextOrderDate()).append(": ")
                        .append(g.lines().stream().map(l -> plain(l.packs()) + " " + (l.packName() == null ? "unid." : l.packName()) + " de " + l.productName()
                                + " (" + priority(l.priority()) + ")").collect(Collectors.joining(", "))).append(". ");
            }
            if (!s.withoutSupplier().isEmpty()) sb.append("Sin proveedor asignado: ").append(s.withoutSupplier().stream()
                    .map(InsightsService.SuggestionLine::productName).collect(Collectors.joining(", "))).append(". ");
            if (s.monthlyBudget() != null && s.monthlyBudget().signum() > 0) {
                sb.append("Presupuesto del mes ").append(Money.fmt(s.monthlyBudget())).append(", gastado ").append(Money.fmt(s.spentThisMonth()))
                        .append(", disponible ").append(Money.fmt(s.budgetLeft())).append('.');
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dinero_necesario_total", s.suggestedTotal());
        data.put("dinero_necesario_critico", s.criticalTotal());
        data.put("presupuesto_mes", s.monthlyBudget());
        data.put("gastado_mes", s.spentThisMonth());
        data.put("presupuesto_disponible", s.budgetLeft());
        data.put("por_proveedor", groups.stream().map(g -> Map.of("proveedor", g.supplierName(), "total_estimado", nz(g.estimatedTotal()),
                "proxima_fecha_pedido", String.valueOf(g.nextOrderDate()), "criticos", g.critical(), "lineas", g.lines().stream().map(this::line).toList())).toList());
        data.put("sin_proveedor", s.withoutSupplier().stream().map(this::line).toList());
        return result(sb.toString().trim(), data);
    }

    private Map<String, Object> line(InsightsService.SuggestionLine l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("producto", l.productName());
        m.put("prioridad", l.priority());
        m.put("existencias", l.stock());
        m.put("en_camino", l.onOrder());
        m.put("venta_diaria", l.dailyDemand());
        m.put("cobertura_dias", l.coverageDays());
        m.put("punto_de_pedido", l.reorderPoint());
        m.put("existencia_objetivo", l.targetStock());
        m.put("unidades_necesarias", l.neededUnits());
        m.put("empaques", l.packs());
        m.put("empaque", l.packName());
        m.put("costo_estimado", l.estimatedCost());
        m.put("razon", l.reason());
        return m;
    }

    private Map<String, Object> declining(JsonNode in) {
        LocalDate[] r = range(in);
        AnalyticsService.Report rep = analytics.report(r[0], r[1]);
        List<AnalyticsService.ProductRow> d = rep.declining();
        String text = d.isEmpty() ? "Ningún producto bajó sus ventas de forma importante frente al período anterior (" + rep.prevFrom() + " a " + rep.prevTo() + ")."
                : d.size() + " productos venden menos que en el período anterior: " + d.stream().limit(10)
                .map(x -> x.name() + " " + Money.fmt(x.prevSales()) + " → " + Money.fmt(x.sales()) + " (" + plain(x.changePct()) + "%)").collect(Collectors.joining("; ")) + ".";
        return result(text, Map.of("periodo_anterior", rep.prevFrom() + " a " + rep.prevTo(), "productos", d));
    }

    private Map<String, Object> compareSuppliers(JsonNode in) {
        List<SupplierService.SupplierDto> list = suppliers.list().stream().filter(SupplierService.SupplierDto::active).toList();
        String q = norm(in.path("producto").asText(""));
        List<Map<String, Object>> prices = new ArrayList<>();
        String productName = null;
        if (!q.isEmpty()) {
            Object[] p = findProduct(q);
            if (p != null) {
                productName = (String) p[1];
                for (SupplierService.CatalogItemDto c : suppliers.forProduct(((Number) p[0]).longValue())) {
                    prices.add(Map.of("proveedor", c.supplierName(), "costo_unidad", nz(c.unitCost()), "empaque", String.valueOf(c.packName()),
                            "costo_empaque", nz(c.costPerPack()), "principal", c.isPrimary()));
                }
                prices.sort(Comparator.comparing(m -> (BigDecimal) m.get("costo_unidad")));
            }
        }
        StringBuilder sb = new StringBuilder(list.isEmpty() ? "No hay proveedores activos registrados. " : "");
        for (SupplierService.SupplierDto s : list) {
            SupplierService.Metrics m = s.metrics();
            sb.append(s.name()).append(": ").append(m.orders()).append(" pedidos, entregas a tiempo ").append(m.onTimePct() == null ? "sin datos" : plain(m.onTimePct()) + "%")
                    .append(", cumplimiento ").append(m.fillRatePct() == null ? "sin datos" : plain(m.fillRatePct()) + "%")
                    .append(", entrega en ").append(m.effectiveLeadDays() == null ? "?" : plain(m.effectiveLeadDays())).append(" días, saldo ").append(Money.fmt(m.balance())).append(". ");
        }
        if (productName != null) {
            sb.append(prices.isEmpty() ? "Ningún proveedor tiene registrado " + productName + "."
                    : "Costo por unidad de " + productName + ": " + prices.stream().map(x -> x.get("proveedor") + " " + Money.fmt((BigDecimal) x.get("costo_unidad")))
                    .collect(Collectors.joining(", ")) + ".");
        } else if (!q.isEmpty()) sb.append("No encontré el producto \"").append(in.path("producto").asText()).append("\".");
        return result(sb.toString().trim(), Map.of("proveedores", list.stream().map(s -> Map.of("proveedor", s.name(), "dias_credito", s.creditDays(),
                "metricas", s.metrics())).toList(), "precios_producto", prices, "producto", String.valueOf(productName)));
    }

    private Map<String, Object> summary(JsonNode in) {
        Long t = TenantContext.require();
        ZoneId z = analytics.zone(t);
        LocalDate today = LocalDate.now(z);
        boolean prev = in.path("anterior").asBoolean(false);
        boolean month = "mes".equals(in.path("tipo").asText("semana"));
        LocalDate from, to;
        if (month) {
            YearMonth ym = prev ? YearMonth.from(today).minusMonths(1) : YearMonth.from(today);
            from = ym.atDay(1);
            to = prev ? ym.atEndOfMonth() : today;
        } else {
            LocalDate monday = today.with(DayOfWeek.MONDAY).minusWeeks(prev ? 1 : 0);
            from = monday;
            to = prev ? monday.plusDays(6) : today;
        }
        String text = notifications.report(t, z, from, to, month ? "del mes" : "de la semana");
        return result("Del " + from + " al " + to + ". " + text.replace('\n', ' '), Map.of("desde", from, "hasta", to, "texto", text));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> anomalies(JsonNode in) {
        Long t = TenantContext.require();
        int days = Math.max(1, Math.min(60, in.path("dias").asInt(7)));
        Timestamp since = Timestamp.from(Instant.now().minus(Duration.ofDays(days)));
        List<String> items = new ArrayList<>();
        for (Object[] r : (List<Object[]>) em.createNativeQuery("""
                        select s.number, s.user_name, s.difference, rg.name from shifts s join registers rg on rg.id = s.register_id
                        where s.tenant_id = :t and s.status = 'CLOSED' and s.closed_at >= :f and coalesce(s.difference,0) <> 0 order by abs(s.difference) desc limit 10""")
                .setParameter("t", t).setParameter("f", since).getResultList()) {
            BigDecimal d = dec(r[2]);
            items.add("Turno #" + r[0] + " de " + r[1] + " en " + r[3] + " cerró con " + (d.signum() < 0 ? "faltante" : "sobrante") + " de " + Money.fmt(d.abs()));
        }
        for (Object[] r : (List<Object[]>) em.createNativeQuery("""
                        select u, sum(v), sum(rf), sum(amount) from (
                          select voided_by u, 1 v, 0 rf, total amount from sales where tenant_id = :t and status = 'ANULADA' and voided_at >= :f
                          union all
                          select user_name, 0, 1, refund_amount from sale_returns where tenant_id = :t and created_at >= :f) x
                        where u is not null group by u having sum(v) + sum(rf) >= 3 order by 2 desc""")
                .setParameter("t", t).setParameter("f", since).getResultList()) {
            items.add(r[0] + " hizo " + r[1] + " anulaciones y " + r[2] + " devoluciones por " + Money.fmt(dec(r[3])));
        }
        Object[] below = (Object[]) em.createNativeQuery("""
                        select count(*), coalesce(sum((l.unit_cost - (l.line_total - l.tax) / nullif(l.quantity,0)) * l.quantity),0)
                        from sale_lines l join sales s on s.id = l.sale_id
                        where s.tenant_id = :t and s.status = 'PAGADA' and s.completed_at >= :f and l.quantity > 0
                          and (l.line_total - l.tax) / l.quantity < l.unit_cost""").setParameter("t", t).setParameter("f", since).getSingleResult();
        if (((Number) below[0]).longValue() > 0) {
            items.add(below[0] + " líneas de venta por debajo del costo (pérdida aproximada " + Money.fmt(dec(below[1])) + ")");
        }
        Object[] disc = (Object[]) em.createNativeQuery("""
                        select count(*), coalesce(sum(discount_total),0) from sales
                        where tenant_id = :t and status = 'PAGADA' and completed_at >= :f and subtotal > 0 and discount_total / subtotal >= 0.2""")
                .setParameter("t", t).setParameter("f", since).getSingleResult();
        if (((Number) disc[0]).longValue() > 0) items.add(disc[0] + " ventas con descuento de 20% o más (" + Money.fmt(dec(disc[1])) + " descontados)");
        Number dup = (Number) em.createNativeQuery("""
                        select count(*) from payments where tenant_id = :t and duplicate_ref and created_at >= :f""")
                .setParameter("t", t).setParameter("f", since).getSingleResult();
        if (dup.longValue() > 0) items.add(dup + " pagos digitales con referencia repetida");
        Number rejected = (Number) em.createNativeQuery("""
                        select count(*) from payments where tenant_id = :t and status = 'RECHAZADO' and created_at >= :f""")
                .setParameter("t", t).setParameter("f", since).getSingleResult();
        if (rejected.longValue() > 0) items.add(rejected + " pagos digitales rechazados (el dinero no llegó)");
        for (InsightsService.Alert a : insights.alerts()) {
            if ("DIFERENCIA_INUSUAL".equals(a.type())) items.add(a.title() + ": " + a.detail());
        }
        Number review = (Number) em.createNativeQuery("""
                        select count(*) from sales where tenant_id = :t and review_flags is not null and reviewed_at is null and completed_at >= :f""")
                .setParameter("t", t).setParameter("f", since).getSingleResult();
        if (review.longValue() > 0) items.add(review + " ventas marcadas para revisión sin revisar (sin internet, stock negativo, pago rechazado…)");
        String text = items.isEmpty() ? "No encontré anomalías en los últimos " + days + " días."
                : "En los últimos " + days + " días hay " + items.size() + " puntos para revisar: " + String.join("; ", items) + ".";
        return result(text, Map.of("dias", days, "hallazgos", items));
    }

    private Map<String, Object> propose(JsonNode in) {
        List<RecommendationService.RecommendationDto> created = recommendations.generate("ASISTENTE");
        List<RecommendationService.RecommendationDto> pending = recommendations.list("PENDIENTE");
        String text = (created.isEmpty() ? "No hay recomendaciones nuevas. " : "Generé " + created.size() + " recomendaciones nuevas. ")
                + (pending.isEmpty() ? "No hay recomendaciones pendientes." : "Pendientes de revisión (" + pending.size() + "): "
                + pending.stream().limit(10).map(r -> r.productName() + ": " + ("PRECIO".equals(r.type())
                ? "subir de " + Money.fmt(r.currentPrice()) + " a " + Money.fmt(r.suggestedPrice())
                : plain(r.discountPct()) + "% de descuento")).collect(Collectors.joining("; "))
                + ". Una persona con permiso de precios debe aprobarlas en Recomendaciones.");
        return result(text, Map.of("nuevas", created, "pendientes", pending));
    }

    private Map<String, Object> inventory(JsonNode in) {
        String q = norm(in.path("buscar").asText(""));
        String filter = in.path("filtro").asText("todos");
        List<InsightsService.ProductInsight> ps = insights.insights().products().stream()
                .filter(p -> q.isEmpty() || norm(p.name() + " " + p.sku()).contains(q))
                .filter(p -> switch (filter) {
                    case "agotados" -> p.flags().contains("AGOTADO");
                    case "por_agotarse" -> p.flags().contains("AGOTAMIENTO_INMINENTE") || p.flags().contains("BAJO_MINIMO");
                    case "sin_rotacion" -> p.flags().contains("SIN_ROTACION");
                    case "exceso" -> p.flags().contains("EXCESO");
                    default -> true;
                }).limit(40).toList();
        String text = ps.isEmpty() ? "No encontré productos con ese criterio." : ps.stream().limit(15).map(p -> p.name() + ": " + plain(p.stock()) + " en existencia"
                + (p.coverageDays() == null ? "" : ", alcanza " + plain(p.coverageDays()) + " días") + (p.flags().isEmpty() ? "" : " [" + String.join(", ", p.flags()).toLowerCase() + "]"))
                .collect(Collectors.joining("; ")) + (ps.size() > 15 ? " y " + (ps.size() - 15) + " más." : ".");
        return result(text, Map.of("productos", ps.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("producto", p.name());
            m.put("existencias", p.stock());
            m.put("minimo", p.stockMin());
            m.put("venta_diaria", p.dailyVelocity());
            m.put("cobertura_dias", p.coverageDays());
            m.put("tendencia_pct", p.trendPct());
            m.put("clase_abc", p.abc());
            m.put("en_camino", p.onOrder());
            m.put("alertas", p.flags());
            return m;
        }).toList()));
    }

    // =====================================================================
    // Utilidades
    // =====================================================================

    @SuppressWarnings("unchecked")
    private Object[] findProduct(String q) {
        List<Object[]> rows = em.createNativeQuery("select id, name from products where tenant_id = :t and status = 'ACTIVE'")
                .setParameter("t", TenantContext.require()).getResultList();
        Object[] best = null;
        for (Object[] r : rows) {
            String n = norm((String) r[1]);
            if (n.equals(q)) return r;
            if (best == null && (n.contains(q) || Arrays.stream(q.split("\\s+")).allMatch(n::contains))) best = r;
        }
        return best;
    }

    private LocalDate[] range(JsonNode in) {
        LocalDate today = LocalDate.now(analytics.zone(TenantContext.require()));
        LocalDate to = date(in.path("hasta").asText(null), today);
        LocalDate from = date(in.path("desde").asText(null), to.withDayOfMonth(1));
        if (from.isAfter(to)) {
            LocalDate x = from;
            from = to;
            to = x;
        }
        if (to.isAfter(today)) to = today;
        if (from.isBefore(to.minusDays(366))) from = to.minusDays(366);
        return new LocalDate[]{from, to};
    }

    private static LocalDate date(String s, LocalDate def) {
        if (s == null || s.isBlank()) return def;
        try {
            return LocalDate.parse(s.trim().substring(0, Math.min(10, s.trim().length())));
        } catch (DateTimeParseException e) {
            return def;
        }
    }

    private static String priority(String p) {
        return switch (p == null ? "" : p) {
            case "CRITICO" -> "crítico";
            case "ALTO" -> "alta prioridad";
            case "NORMAL" -> "normal";
            default -> p == null ? "" : p.toLowerCase();
        };
    }

    private static Map<String, Object> result(String resumen, Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resumen", resumen);
        m.put("datos", data);
        return m;
    }

    static String norm(String s) {
        return s == null ? "" : Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.setScale(Math.min(1, Math.max(0, v.scale())), RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal dec(Object o) {
        return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }
}
