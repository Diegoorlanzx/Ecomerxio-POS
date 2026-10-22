package com.tiendaposs.distribution;

import com.tiendaposs.analytics.AnalyticsService;
import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogRepos.PresentationRepository;
import com.tiendaposs.catalog.Presentation;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.credit.CreditService;
import com.tiendaposs.distribution.DistributionRepos.*;
import com.tiendaposs.pos.Customer;
import com.tiendaposs.pos.PosRepos.CustomerRepository;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.ModulesService;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Fase 12 — Distribución: listas de precios por tipo de cliente con descuento por volumen, rutas con vendedor y días
 * de visita, lo que el vendedor necesita en el celular (también sin internet) y los reportes por vendedor y ruta.
 */
@Service
public class DistributionService {

    private final PriceListRepository lists;
    private final PriceListItemRepository items;
    private final SalesRouteRepository routes;
    private final RouteVisitRepository visits;
    private final PresaleOrderRepository orders;
    private final CustomerRepository customers;
    private final PresentationRepository presentations;
    private final CreditService credit;
    private final AnalyticsService analytics;
    private final SettingsService settings;
    private final AuditService audit;
    private final EntityManager em;

    public DistributionService(PriceListRepository lists, PriceListItemRepository items, SalesRouteRepository routes, RouteVisitRepository visits,
                               PresaleOrderRepository orders, CustomerRepository customers, PresentationRepository presentations,
                               CreditService credit, AnalyticsService analytics, SettingsService settings, AuditService audit, EntityManager em) {
        this.lists = lists;
        this.items = items;
        this.routes = routes;
        this.visits = visits;
        this.orders = orders;
        this.customers = customers;
        this.presentations = presentations;
        this.credit = credit;
        this.analytics = analytics;
        this.settings = settings;
        this.audit = audit;
        this.em = em;
    }

    Long tenant() {
        Long t = TenantContext.require();
        ModulesService.check(settings.forTenant(t), "DISTRIBUCION");
        return t;
    }

    ZoneId zone(Long t) {
        return analytics.zone(t);
    }

    // =====================================================================
    // Listas de precios
    // =====================================================================

    public record ItemDto(Long presentationId, BigDecimal minQty, BigDecimal price) {
    }

    public record PriceListDto(Long id, String name, BigDecimal discountPct, boolean active, List<ItemDto> items, long customers) {
    }

    public record PriceListRequest(@NotBlank(message = "ponle un nombre") @Size(max = 80) String name,
                                   @DecimalMin(value = "0", message = "no puede ser negativo") @DecimalMax(value = "90", message = "máximo 90") BigDecimal discountPct,
                                   Boolean active,
                                   List<@Valid ItemRequest> items) {
    }

    public record ItemRequest(@NotNull Long presentationId,
                              @DecimalMin(value = "0.001", message = "la cantidad debe ser mayor que cero") BigDecimal minQty,
                              @NotNull @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal price) {
    }

    @Transactional(readOnly = true)
    public List<PriceListDto> priceLists() {
        Long t = tenant();
        Map<Long, Long> used = customers.findByTenantIdAndActiveTrue(t).stream().filter(c -> c.priceListId != null)
                .collect(Collectors.groupingBy(c -> c.priceListId, Collectors.counting()));
        return lists.findByTenantIdOrderByNameAsc(t).stream().map(l -> toDto(l, used.getOrDefault(l.id, 0L))).toList();
    }

    PriceListDto toDto(PriceList l, long used) {
        return new PriceListDto(l.id, l.name, l.discountPct, l.active, items.findByListIdOrderByPresentationIdAscMinQtyAsc(l.id).stream()
                .map(i -> new ItemDto(i.presentationId, i.minQty, i.price)).toList(), used);
    }

    @Transactional
    public PriceListDto savePriceList(Long id, PriceListRequest r) {
        CurrentUser.require(Permission.PRODUCTOS_PRECIOS);
        Long t = tenant();
        PriceList l = id == null ? new PriceList() : lists.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Lista no encontrada"));
        l.tenantId = t;
        l.name = r.name().trim();
        l.discountPct = r.discountPct() == null ? BigDecimal.ZERO : r.discountPct().setScale(2, RoundingMode.HALF_UP);
        if (r.active() != null) l.active = r.active();
        l = lists.save(l);
        if (r.items() != null) {
            Set<String> seen = new HashSet<>();
            List<PriceListItem> out = new ArrayList<>();
            for (ItemRequest i : r.items()) {
                presentations.findByIdAndTenantId(i.presentationId(), t).orElseThrow(() -> ApiException.field("items", "un producto de la lista no existe"));
                BigDecimal min = (i.minQty() == null ? BigDecimal.ONE : i.minQty()).setScale(3, RoundingMode.HALF_UP);
                if (!seen.add(i.presentationId() + ":" + min.stripTrailingZeros().toPlainString())) {
                    throw ApiException.field("items", "un producto tiene dos precios para la misma cantidad");
                }
                PriceListItem it = new PriceListItem();
                it.tenantId = t;
                it.listId = l.id;
                it.presentationId = i.presentationId();
                it.minQty = min;
                it.price = i.price().setScale(2, RoundingMode.HALF_UP);
                out.add(it);
            }
            items.deleteByListId(l.id);
            items.flush();
            items.saveAll(out);
        }
        audit.log(id == null ? "LISTA_PRECIOS_CREADA" : "LISTA_PRECIOS_EDITADA", "PRICE_LIST", l.id, l.name);
        return toDto(l, 0);
    }

    /**
     * Precio para un cliente: el de su lista para esa cantidad (el escalón más alto que alcance), si no el descuento
     * general de la lista sobre el precio de venta, si no el precio de venta.
     */
    BigDecimal price(Long t, Long listId, Presentation pr, BigDecimal qty) {
        if (listId == null) return pr.price;
        PriceList l = lists.findByIdAndTenantId(listId, t).filter(x -> x.active).orElse(null);
        if (l == null) return pr.price;
        Optional<PriceListItem> best = items.findByListIdOrderByPresentationIdAscMinQtyAsc(l.id).stream()
                .filter(i -> i.presentationId.equals(pr.id) && i.minQty.compareTo(qty) <= 0)
                .max(Comparator.comparing(i -> i.minQty));
        if (best.isPresent()) return best.get().price;
        if (l.discountPct.signum() > 0) {
            return pr.price.multiply(BigDecimal.valueOf(100).subtract(l.discountPct)).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                    .setScale(2, RoundingMode.HALF_UP);
        }
        return pr.price;
    }

    // =====================================================================
    // Rutas y clientes
    // =====================================================================

    public record RouteDto(Long id, String name, Long sellerUserId, String sellerName, List<Integer> visitDays, BigDecimal commissionPct,
                           boolean active, long customers) {
    }

    public record RouteRequest(@NotBlank(message = "ponle un nombre") @Size(max = 80) String name, Long sellerUserId,
                               List<@Min(1) @Max(7) Integer> visitDays,
                               @DecimalMin(value = "0", message = "no puede ser negativo") @DecimalMax(value = "100", message = "máximo 100") BigDecimal commissionPct,
                               Boolean active) {
    }

    @Transactional(readOnly = true)
    public List<RouteDto> routes() {
        Long t = tenant();
        Map<Long, Long> count = customers.findByTenantIdAndActiveTrue(t).stream().filter(c -> c.routeId != null)
                .collect(Collectors.groupingBy(c -> c.routeId, Collectors.counting()));
        return routes.findByTenantIdOrderByNameAsc(t).stream().map(r -> toDto(r, count.getOrDefault(r.id, 0L))).toList();
    }

    RouteDto toDto(SalesRoute r, long n) {
        return new RouteDto(r.id, r.name, r.sellerUserId, r.sellerName, days(r.visitDays), r.commissionPct, r.active, n);
    }

    static List<Integer> days(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(x -> !x.isEmpty()).map(Integer::valueOf).sorted().toList();
    }

    @Transactional
    public RouteDto saveRoute(Long id, RouteRequest r) {
        CurrentUser.require(Permission.CONFIG_EDITAR);
        Long t = tenant();
        SalesRoute x = id == null ? new SalesRoute() : routes.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Ruta no encontrada"));
        x.tenantId = t;
        x.name = r.name().trim();
        x.sellerUserId = r.sellerUserId();
        if (r.sellerUserId() != null) {
            List<?> names = em.createNativeQuery("select full_name from users where id = :u and tenant_id = :t")
                    .setParameter("u", r.sellerUserId()).setParameter("t", t).getResultList();
            if (names.isEmpty()) throw ApiException.field("sellerUserId", "ese usuario no existe");
            x.sellerName = String.valueOf(names.get(0));
        } else {
            x.sellerName = null;
        }
        x.visitDays = r.visitDays() == null ? "" : r.visitDays().stream().distinct().sorted().map(String::valueOf).collect(Collectors.joining(","));
        x.commissionPct = r.commissionPct() == null ? BigDecimal.ZERO : r.commissionPct().setScale(2, RoundingMode.HALF_UP);
        if (r.active() != null) x.active = r.active();
        x = routes.save(x);
        audit.log(id == null ? "RUTA_CREADA" : "RUTA_EDITADA", "ROUTE", x.id, x.name);
        return toDto(x, 0);
    }

    public record CustomerRouteRequest(Long routeId, Long priceListId, Integer routeOrder) {
    }

    public record RouteCustomer(Long id, String name, String phone, String address, Long routeId, int routeOrder, Long priceListId) {
    }

    @Transactional(readOnly = true)
    public List<RouteCustomer> routeCustomers() {
        Long t = tenant();
        return customers.findByTenantIdAndActiveTrue(t).stream()
                .sorted(Comparator.comparing((Customer c) -> c.routeId == null ? Long.MAX_VALUE : c.routeId).thenComparing(c -> c.routeOrder)
                        .thenComparing(c -> c.name, String.CASE_INSENSITIVE_ORDER))
                .map(c -> new RouteCustomer(c.id, c.name, c.phone, c.address, c.routeId, c.routeOrder, c.priceListId)).toList();
    }

    @Transactional
    public RouteCustomer assign(Long customerId, CustomerRouteRequest r) {
        CurrentUser.require(Permission.CONFIG_EDITAR);
        Long t = tenant();
        Customer c = customers.findByIdAndTenantId(customerId, t).orElseThrow(() -> ApiException.notFound("Cliente no encontrado"));
        if (r.routeId() != null) routes.findByIdAndTenantId(r.routeId(), t).orElseThrow(() -> ApiException.field("routeId", "esa ruta no existe"));
        if (r.priceListId() != null) lists.findByIdAndTenantId(r.priceListId(), t).orElseThrow(() -> ApiException.field("priceListId", "esa lista no existe"));
        c.routeId = r.routeId();
        c.priceListId = r.priceListId();
        if (r.routeOrder() != null) c.routeOrder = r.routeOrder();
        customers.save(c);
        return new RouteCustomer(c.id, c.name, c.phone, c.address, c.routeId, c.routeOrder, c.priceListId);
    }

    // =====================================================================
    // Lo que el vendedor lleva en el celular (se guarda para trabajar sin internet)
    // =====================================================================

    public record SellerCustomer(Long id, String name, String phone, String address, Long routeId, int routeOrder, Long priceListId,
                                 boolean today, BigDecimal balance, BigDecimal overdue, boolean blocked, String visitResult, Long orderToday) {
    }

    public record SellerProduct(Long productId, Long presentationId, String name, String presentationName, BigDecimal price,
                                BigDecimal stock, BigDecimal factor, String unit, Long categoryId) {
    }

    public record SellerData(LocalDate date, int weekday, List<RouteDto> routes, List<SellerCustomer> customers, List<PriceListDto> priceLists,
                             List<SellerProduct> products, boolean creditEnabled) {
    }

    /** Rutas del vendedor (todas para quien administra), sus clientes con saldo y lo de hoy, listas y productos. */
    @Transactional(readOnly = true)
    public SellerData sellerData() {
        CurrentUser.require(Permission.POS_VENDER);
        Long t = tenant();
        AuthUser me = CurrentUser.get();
        ZoneId z = zone(t);
        LocalDate today = LocalDate.now(z);
        int wd = today.getDayOfWeek().getValue();
        boolean admin = me.can(Permission.CONFIG_EDITAR);
        List<SalesRoute> mine = routes.findByTenantIdOrderByNameAsc(t).stream().filter(r -> r.active && (admin || me.userId().equals(r.sellerUserId))).toList();
        Set<Long> routeIds = mine.stream().map(r -> r.id).collect(Collectors.toSet());
        Map<Long, SalesRoute> byId = mine.stream().collect(Collectors.toMap(r -> r.id, r -> r));
        Map<Long, CreditService.CustomerCredit> balances = credit.list(false).stream().collect(Collectors.toMap(CreditService.CustomerCredit::customerId, x -> x, (a, b) -> a));
        Map<Long, RouteVisit> todayVisits = visits.findByTenantIdAndVisitDateBetween(t, today, today).stream()
                .collect(Collectors.toMap(v -> v.customerId, v -> v, (a, b) -> b));
        List<SellerCustomer> cs = new ArrayList<>();
        for (Customer c : customers.findByTenantIdAndActiveTrue(t)) {
            if (c.routeId == null || !routeIds.contains(c.routeId)) continue;
            var b = balances.get(c.id);
            RouteVisit v = todayVisits.get(c.id);
            cs.add(new SellerCustomer(c.id, c.name, c.phone, c.address, c.routeId, c.routeOrder, c.priceListId,
                    days(byId.get(c.routeId).visitDays).contains(wd), b == null ? BigDecimal.ZERO : b.balance(),
                    b == null ? BigDecimal.ZERO : b.overdue(), b != null && b.blocked(), v == null ? null : v.result, v == null ? null : v.orderId));
        }
        cs.sort(Comparator.comparing((SellerCustomer c) -> !c.today()).thenComparing(SellerCustomer::routeId).thenComparing(SellerCustomer::routeOrder)
                .thenComparing(SellerCustomer::name, String.CASE_INSENSITIVE_ORDER));
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                        select p.id, pr.id, p.name, pr.name, pr.price, p.stock - coalesce(p.reserved, 0), pr.factor, p.unit, p.category_id
                        from products p join product_presentations pr on pr.product_id = p.id
                        where p.tenant_id = :t and p.status = 'ACTIVE' and pr.active and p.product_type <> 'PADRE'
                          and p.service_minutes is null and coalesce(p.sku, '') not in ('DOMICILIO', 'VARIOS')
                        order by p.name, pr.sort_order, pr.id limit 3000""").setParameter("t", t).getResultList();
        List<SellerProduct> ps = rows.stream().map(r -> new SellerProduct(((Number) r[0]).longValue(), ((Number) r[1]).longValue(), (String) r[2],
                (String) r[3], dec(r[4]), dec(r[5]), dec(r[6]), (String) r[7], r[8] == null ? null : ((Number) r[8]).longValue())).toList();
        List<PriceListDto> pls = lists.findByTenantIdOrderByNameAsc(t).stream().filter(l -> l.active).map(l -> toDto(l, 0)).toList();
        return new SellerData(today, wd, mine.stream().map(r -> toDto(r, 0)).toList(), cs, pls, ps, settings.forTenant(t).creditEnabled);
    }

    // =====================================================================
    // Reportes
    // =====================================================================

    public record SellerRow(Long sellerUserId, String sellerName, long orders, BigDecimal ordered, BigDecimal delivered, BigDecimal commission) {
    }

    public record RouteRow(Long routeId, String routeName, String sellerName, long customers, long scheduledVisits, long visits, long orders,
                           BigDecimal effectivenessPct, BigDecimal delivered, BigDecimal returned, BigDecimal overdue) {
    }

    public record Report(LocalDate from, LocalDate to, List<SellerRow> sellers, List<RouteRow> routes) {
    }

    @Transactional(readOnly = true)
    public Report report(LocalDate from, LocalDate to) {
        CurrentUser.require(Permission.REPORTES_VER);
        Long t = tenant();
        ZoneId z = zone(t);
        LocalDate today = LocalDate.now(z);
        LocalDate f = from == null ? today.withDayOfMonth(1) : from;
        LocalDate tt = to == null ? today : to;
        var fi = f.atStartOfDay(z).toInstant();
        var ti = tt.plusDays(1).atStartOfDay(z).toInstant();
        List<PresaleOrder> os = orders.findByTenantIdOrderByTakenAtDesc(t).stream()
                .filter(o -> !o.takenAt.isBefore(fi) && o.takenAt.isBefore(ti) && !"ANULADO".equals(o.status)).toList();
        Map<Long, SalesRoute> rs = routes.findByTenantIdOrderByNameAsc(t).stream().collect(Collectors.toMap(r -> r.id, r -> r, (a, b) -> a, LinkedHashMap::new));

        Map<String, List<PresaleOrder>> bySeller = os.stream().collect(Collectors.groupingBy(o -> o.sellerUserId + "|" + o.sellerName));
        List<SellerRow> sellers = new ArrayList<>();
        bySeller.forEach((k, list) -> {
            BigDecimal ordered = sum(list, o -> o.total), delivered = sum(list, o -> o.deliveredTotal);
            BigDecimal com = list.stream().map(o -> {
                SalesRoute r = o.routeId == null ? null : rs.get(o.routeId);
                return r == null ? BigDecimal.ZERO : o.deliveredTotal.multiply(r.commissionPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            }).reduce(BigDecimal.ZERO, BigDecimal::add);
            PresaleOrder first = list.get(0);
            sellers.add(new SellerRow(first.sellerUserId, first.sellerName, list.size(), ordered, delivered, com));
        });
        sellers.sort(Comparator.comparing(SellerRow::delivered).reversed());

        List<RouteVisit> vs = visits.findByTenantIdAndVisitDateBetween(t, f, tt);
        Map<Long, CreditService.CustomerCredit> balances = credit.list(true).stream().collect(Collectors.toMap(CreditService.CustomerCredit::customerId, x -> x, (a, b) -> a));
        List<Customer> all = customers.findByTenantIdAndActiveTrue(t).stream().filter(c -> c.routeId != null).toList();
        List<RouteRow> rows = new ArrayList<>();
        for (SalesRoute r : rs.values()) {
            List<Customer> cs = all.stream().filter(c -> r.id.equals(c.routeId)).toList();
            List<Integer> ds = days(r.visitDays);
            long scheduledDays = 0;
            // Desde que existe la ruta (no se cuentan visitas de antes de crearla).
            LocalDate start = f.isBefore(LocalDate.ofInstant(r.createdAt, z)) ? LocalDate.ofInstant(r.createdAt, z) : f;
            for (LocalDate d = start; !d.isAfter(tt) && !d.isAfter(today); d = d.plusDays(1)) if (ds.contains(d.getDayOfWeek().getValue())) scheduledDays++;
            long scheduled = scheduledDays * cs.size();
            long v = vs.stream().filter(x -> r.id.equals(x.routeId)).count();
            List<PresaleOrder> ro = os.stream().filter(o -> r.id.equals(o.routeId)).toList();
            BigDecimal eff = scheduled == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(ro.size() * 100L).divide(BigDecimal.valueOf(scheduled), 1, RoundingMode.HALF_UP);
            BigDecimal returned = ro.stream().map(o -> o.total.subtract(o.deliveredTotal))
                    .filter(x -> x.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal overdue = cs.stream().map(c -> balances.containsKey(c.id) ? balances.get(c.id).overdue() : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add);
            rows.add(new RouteRow(r.id, r.name, r.sellerName, cs.size(), scheduled, v, ro.size(), eff,
                    sum(ro.stream().filter(o -> o.deliveredAt != null).toList(), o -> o.deliveredTotal), returned, overdue));
        }
        return new Report(f, tt, sellers, rows);
    }

    private static <T> BigDecimal sum(List<T> list, java.util.function.Function<T, BigDecimal> f) {
        return list.stream().map(f).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    boolean settingsCredit(Long t) {
        var st = settings.forTenant(t);
        return st.creditEnabled && st.paymentMethodList().contains("FIADO");
    }

    static BigDecimal dec(Object o) {
        return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(o));
    }
}
