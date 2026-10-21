package com.tiendaposs.restaurant;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogRepos.CategoryRepository;
import com.tiendaposs.catalog.CatalogRepos.PresentationRepository;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Category;
import com.tiendaposs.catalog.Presentation;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Money;
import com.tiendaposs.pos.PosRepos.SaleLineRepository;
import com.tiendaposs.pos.PosRepos.SaleRepository;
import com.tiendaposs.pos.Sale;
import com.tiendaposs.pos.SaleDtos.*;
import com.tiendaposs.pos.SaleService;
import com.tiendaposs.restaurant.RestaurantRepos.*;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.ModulesService;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.settings.StoreSettings;
import com.tiendaposs.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Fase 10 — Restaurante. Mesas y salones sobre las cuentas abiertas (una mesa ocupada = una cuenta abierta con mesa),
 * modificadores con precio, platos con nota para la cocina, precuenta, dividir/unir/mover cuentas y propina voluntaria.
 */
@Service
public class RestaurantService {

    private final DiningAreaRepository areas;
    private final DiningTableRepository tables;
    private final ModifierGroupRepository groups;
    private final ModifierOptionRepository options;
    private final SaleRepository sales;
    private final SaleLineRepository lines;
    private final SaleService saleService;
    private final KitchenService kitchen;
    private final ProductRepository products;
    private final PresentationRepository presentations;
    private final CategoryRepository categories;
    private final SettingsService settings;
    private final AuditService audit;
    private final EntityManager em;

    public RestaurantService(DiningAreaRepository areas, DiningTableRepository tables, ModifierGroupRepository groups,
                             ModifierOptionRepository options, SaleRepository sales, SaleLineRepository lines, SaleService saleService,
                             KitchenService kitchen, ProductRepository products, PresentationRepository presentations,
                             CategoryRepository categories, SettingsService settings, AuditService audit, EntityManager em) {
        this.areas = areas;
        this.tables = tables;
        this.groups = groups;
        this.options = options;
        this.sales = sales;
        this.lines = lines;
        this.saleService = saleService;
        this.kitchen = kitchen;
        this.products = products;
        this.presentations = presentations;
        this.categories = categories;
        this.settings = settings;
        this.audit = audit;
        this.em = em;
    }

    private Long tenant() {
        Long t = TenantContext.require();
        ModulesService.check(settings.forTenant(t), "RESTAURANTE");
        return t;
    }

    // =====================================================================
    // Plano: salones, mesas y su estado
    // =====================================================================

    public record AccountOnTable(Long id, String label, String waiterName, BigDecimal total, int items, int guests, long minutes,
                                 boolean prebill, int ready, int cooking, int pendingToSend) {
    }

    public record TableDto(Long id, Long areaId, String name, int seats, int sortOrder, String state, List<AccountOnTable> accounts) {
    }

    public record AreaDto(Long id, String name, int sortOrder) {
    }

    public record FloorDto(List<AreaDto> areas, List<TableDto> tables, boolean tipsEnabled, BigDecimal tipSuggestedPct) {
    }

    /**
     * Estado de cada mesa: LIBRE, OCUPADA, POR_COBRAR (se imprimió la precuenta) o LISTO (hay platos listos para llevar).
     */
    @Transactional(readOnly = true)
    public FloorDto floor() {
        Long t = tenant();
        StoreSettings st = settings.forTenant(t);
        Instant now = Instant.now();
        Map<Long, List<Sale>> byTable = sales.findByTenantIdAndStatusInOrderByCreatedAtAsc(t, List.of("ABIERTA")).stream()
                .filter(s -> s.tableId != null).collect(Collectors.groupingBy(s -> s.tableId));
        List<TableDto> out = new ArrayList<>();
        for (DiningTable tb : tables.findByTenantIdAndActiveTrueOrderBySortOrderAscIdAsc(t)) {
            List<AccountOnTable> accs = new ArrayList<>();
            boolean prebill = false, ready = false;
            for (Sale s : byTable.getOrDefault(tb.id, List.of())) {
                var ls = lines.findBySaleIdOrderBySortOrderAscIdAsc(s.id);
                int items = ls.stream().map(l -> l.quantity).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(0, RoundingMode.UP).intValue();
                int pending = (int) ls.stream().filter(l -> l.quantity.compareTo(l.sentQty) != 0).count();
                int[] k = kitchen.counts(s.id);
                accs.add(new AccountOnTable(s.id, s.accountLabel, s.userName, s.total, items, s.guests == null ? 0 : s.guests,
                        Duration.between(s.createdAt, now).toMinutes(), s.prebillAt != null, k[0], k[1], pending));
                prebill |= s.prebillAt != null;
                ready |= k[0] > 0;
            }
            String state = accs.isEmpty() ? "LIBRE" : ready ? "LISTO" : prebill ? "POR_COBRAR" : "OCUPADA";
            out.add(new TableDto(tb.id, tb.areaId, tb.name, tb.seats, tb.sortOrder, state, accs));
        }
        List<AreaDto> as = areas.findByTenantIdAndActiveTrueOrderBySortOrderAscIdAsc(t).stream()
                .map(a -> new AreaDto(a.id, a.name, a.sortOrder)).toList();
        return new FloorDto(as, out, st.tipsEnabled, st.tipSuggestedPct);
    }

    public record AreaRequest(@NotBlank(message = "ponle un nombre") @Size(max = 60) String name, Integer sortOrder) {
    }

    @Transactional
    public AreaDto saveArea(Long id, AreaRequest r) {
        Long t = tenant();
        CurrentUser.require(Permission.CONFIG_EDITAR);
        DiningArea a = id == null ? new DiningArea() : areas.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Salón no encontrado"));
        a.tenantId = t;
        a.name = r.name().trim();
        if (r.sortOrder() != null) a.sortOrder = r.sortOrder();
        a = areas.save(a);
        return new AreaDto(a.id, a.name, a.sortOrder);
    }

    @Transactional
    public void deleteArea(Long id) {
        Long t = tenant();
        CurrentUser.require(Permission.CONFIG_EDITAR);
        DiningArea a = areas.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Salón no encontrado"));
        a.active = false;
        for (DiningTable tb : tables.findByTenantIdAndActiveTrueOrderBySortOrderAscIdAsc(t)) if (id.equals(tb.areaId)) tb.areaId = null;
    }

    public record TableRequest(@NotBlank(message = "ponle un nombre (Mesa 1, Barra 2…)") @Size(max = 40) String name, Long areaId,
                               @Min(value = 1, message = "mínimo 1 puesto") @Max(value = 40, message = "máximo 40") Integer seats,
                               Integer sortOrder) {
    }

    @Transactional
    public TableDto saveTable(Long id, TableRequest r) {
        Long t = tenant();
        CurrentUser.require(Permission.CONFIG_EDITAR);
        DiningTable tb = id == null ? new DiningTable() : tables.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Mesa no encontrada"));
        String name = r.name().trim();
        for (DiningTable o : tables.findByTenantIdAndActiveTrueOrderBySortOrderAscIdAsc(t)) {
            if (!o.id.equals(tb.id) && o.name.equalsIgnoreCase(name)) throw ApiException.field("name", "ya hay una mesa con ese nombre");
        }
        if (r.areaId() != null) areas.findByIdAndTenantId(r.areaId(), t).orElseThrow(() -> ApiException.field("areaId", "el salón no existe"));
        tb.tenantId = t;
        tb.name = name;
        tb.areaId = r.areaId();
        if (r.seats() != null) tb.seats = r.seats();
        if (r.sortOrder() != null) tb.sortOrder = r.sortOrder();
        tb = tables.save(tb);
        return new TableDto(tb.id, tb.areaId, tb.name, tb.seats, tb.sortOrder, "LIBRE", List.of());
    }

    @Transactional
    public void deleteTable(Long id) {
        Long t = tenant();
        CurrentUser.require(Permission.CONFIG_EDITAR);
        DiningTable tb = tables.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Mesa no encontrada"));
        boolean busy = sales.findByTenantIdAndStatusInOrderByCreatedAtAsc(t, List.of("ABIERTA")).stream().anyMatch(s -> id.equals(s.tableId));
        if (busy) throw ApiException.conflict(tb.name + " tiene una cuenta abierta: cóbrala o muévela antes de quitar la mesa");
        tb.active = false;
    }

    public record OpenTableRequest(@Min(value = 1, message = "mínimo 1 persona") @Max(value = 99) Integer guests) {
    }

    /** Abre la mesa (o devuelve su cuenta si ya está abierta). El mesero queda como responsable de la cuenta. */
    @Transactional
    public SaleDto openTable(Long tableId, OpenTableRequest r) {
        Long t = tenant();
        DiningTable tb = tables.findByIdAndTenantId(tableId, t).filter(x -> x.active).orElseThrow(() -> ApiException.notFound("Mesa no encontrada"));
        Optional<Sale> open = sales.findByTenantIdAndStatusInOrderByCreatedAtAsc(t, List.of("ABIERTA")).stream()
                .filter(s -> tableId.equals(s.tableId)).findFirst();
        if (open.isPresent()) return saleService.get(open.get().id);
        SaleDto dto = saleService.openAccount(new AccountRequest(tb.name, null, null));
        Sale s = sales.findById(dto.id()).orElseThrow();
        s.tableId = tb.id;
        s.guests = r == null ? null : r.guests();
        sales.save(s);
        return saleService.get(s.id);
    }

    public record MoveRequest(@NotNull Long tableId) {
    }

    /** Cambia la cuenta de mesa (el grupo se pasó a otra). La mesa nueva debe estar libre. */
    @Transactional
    public SaleDto move(Long accountId, MoveRequest r) {
        Long t = tenant();
        Sale s = openAccount(accountId, t);
        DiningTable tb = tables.findByIdAndTenantId(r.tableId(), t).filter(x -> x.active).orElseThrow(() -> ApiException.notFound("Mesa no encontrada"));
        boolean busy = sales.findByTenantIdAndStatusInOrderByCreatedAtAsc(t, List.of("ABIERTA")).stream()
                .anyMatch(o -> tb.id.equals(o.tableId) && !o.id.equals(s.id));
        if (busy) throw ApiException.conflict(tb.name + " está ocupada: usa \"Unir\" para juntar las cuentas");
        String old = s.accountLabel;
        s.tableId = tb.id;
        s.accountLabel = tb.name;
        sales.save(s);
        audit.log("MESA_MOVIDA", "SALE", s.id, "Pasó la cuenta de " + old + " a " + tb.name);
        return saleService.get(s.id);
    }

    public record MergeRequest(@NotNull Long intoAccountId) {
    }

    public SaleDto merge(Long accountId, MergeRequest r) {
        tenant();
        return saleService.mergeAccounts(accountId, r.intoAccountId());
    }

    public record SplitRequest(@NotEmpty(message = "elige qué productos pasan a la otra cuenta") List<SaleService.SplitLine> lines) {
    }

    public SaleDto split(Long accountId, SplitRequest r) {
        tenant();
        return saleService.splitAccount(accountId, r.lines());
    }

    /** Precuenta: la mesa queda "por cobrar" (el comprobante se imprime con la vista de recibo de la cuenta). */
    @Transactional
    public SaleDto prebill(Long accountId) {
        Long t = tenant();
        Sale s = openAccount(accountId, t);
        s.prebillAt = Instant.now();
        sales.save(s);
        audit.log("PRECUENTA", "SALE", s.id, "Imprimió la precuenta de " + s.accountLabel + " por " + Money.fmt(s.total));
        return saleService.get(s.id);
    }

    private Sale openAccount(Long id, Long t) {
        Sale s = sales.findById(id).filter(x -> t.equals(x.tenantId)).orElseThrow(() -> ApiException.notFound("La cuenta ya no existe"));
        if (!"ABIERTA".equals(s.status)) throw ApiException.conflict("Esa cuenta ya no está abierta");
        return s;
    }

    // =====================================================================
    // Platos con modificadores
    // =====================================================================

    public record DishRequest(@NotNull Long presentationId,
                              @NotNull @DecimalMin(value = "0.001", message = "la cantidad debe ser mayor que cero") BigDecimal quantity,
                              List<Long> modifierIds, @Size(max = 200) String note, List<String> approvals) {
    }

    /**
     * Agrega un plato: valida los modificadores contra los grupos del producto (mínimos y máximos) y suma su precio al
     * precio del plato. El precio lo calcula el servidor, nunca la caja.
     */
    @Transactional
    public SaleDto addDish(Long accountId, DishRequest r) {
        Long t = tenant();
        Presentation pr = presentations.findByIdAndTenantId(r.presentationId(), t).orElseThrow(() -> ApiException.notFound("Producto no encontrado"));
        Product p = products.findByIdAndTenantId(pr.productId, t).orElseThrow();
        List<Long> ids = r.modifierIds() == null ? List.of() : r.modifierIds().stream().distinct().toList();
        List<ModifierOption> chosen = ids.isEmpty() ? List.of() : options.findByIdInAndTenantId(ids, t);
        if (chosen.size() != ids.size()) throw ApiException.field("modifierIds", "una opción ya no existe");
        List<Long> groupIds = productGroups(p.id);
        Map<Long, ModifierGroup> gs = new LinkedHashMap<>();
        for (Long gid : groupIds) groups.findByIdAndTenantId(gid, t).filter(g -> g.active).ifPresent(g -> gs.put(g.id, g));
        for (ModifierOption o : chosen) {
            if (!gs.containsKey(o.groupId) || !o.active) throw ApiException.field("modifierIds", o.name + " no aplica a " + p.name);
        }
        for (ModifierGroup g : gs.values()) {
            long n = chosen.stream().filter(o -> o.groupId.equals(g.id)).count();
            if (n < g.minSelect) throw ApiException.field("modifierIds", "elige " + (g.minSelect == 1 ? "una opción" : g.minSelect + " opciones") + " de \"" + g.name + "\"");
            if (g.maxSelect > 0 && n > g.maxSelect) throw ApiException.field("modifierIds", "en \"" + g.name + "\" se eligen máximo " + g.maxSelect);
        }
        BigDecimal extra = chosen.stream().map(o -> o.price).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Orden de los modificadores: el de los grupos y luego el de cada opción.
        List<Long> order = new ArrayList<>(gs.keySet());
        String mods = chosen.stream()
                .sorted(Comparator.comparing((ModifierOption o) -> order.indexOf(o.groupId)).thenComparing(o -> o.sortOrder))
                .map(o -> o.name + (o.price.signum() > 0 ? " (+" + Money.fmt(o.price) + ")" : "")).collect(Collectors.joining(" · "));
        BigDecimal unitPrice = extra.signum() > 0 ? pr.price.add(extra).setScale(2, RoundingMode.HALF_UP) : null;
        return saleService.addDishLine(accountId, new LineRequest(pr.id, r.quantity(), unitPrice, null), r.approvals(),
                mods.isEmpty() ? null : mods, r.note());
    }

    // =====================================================================
    // Modificadores (configuración)
    // =====================================================================

    public record OptionDto(Long id, String name, BigDecimal price, int sortOrder) {
    }

    public record GroupDto(Long id, String name, int minSelect, int maxSelect, int sortOrder, List<OptionDto> options, List<Long> productIds) {
    }

    @SuppressWarnings("unchecked")
    private List<Long> productGroups(Long productId) {
        return ((List<Number>) em.createNativeQuery("select group_id from product_modifier_groups where product_id = :p order by sort_order, group_id")
                .setParameter("p", productId).getResultList()).stream().map(Number::longValue).toList();
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<GroupDto> modifiers() {
        Long t = tenant();
        Map<Long, List<OptionDto>> opts = options.findByTenantIdAndActiveTrueOrderBySortOrderAscIdAsc(t).stream()
                .collect(Collectors.groupingBy(o -> o.groupId, LinkedHashMap::new,
                        Collectors.mapping(o -> new OptionDto(o.id, o.name, o.price, o.sortOrder), Collectors.toList())));
        Map<Long, List<Long>> prods = new HashMap<>();
        for (Object[] row : (List<Object[]>) em.createNativeQuery("""
                        select pmg.group_id, pmg.product_id from product_modifier_groups pmg join modifier_groups g on g.id = pmg.group_id
                        where g.tenant_id = :t order by pmg.sort_order""").setParameter("t", t).getResultList()) {
            prods.computeIfAbsent(((Number) row[0]).longValue(), k -> new ArrayList<>()).add(((Number) row[1]).longValue());
        }
        return groups.findByTenantIdAndActiveTrueOrderBySortOrderAscIdAsc(t).stream()
                .map(g -> new GroupDto(g.id, g.name, g.minSelect, g.maxSelect, g.sortOrder, opts.getOrDefault(g.id, List.of()),
                        prods.getOrDefault(g.id, List.of()))).toList();
    }

    public record OptionRequest(Long id, @NotBlank(message = "ponle un nombre a la opción") @Size(max = 60) String name,
                                @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal price) {
    }

    public record GroupRequest(@NotBlank(message = "ponle un nombre (Término, Adiciones…)") @Size(max = 60) String name,
                               @Min(0) Integer minSelect, @Min(0) Integer maxSelect,
                               @NotEmpty(message = "agrega al menos una opción") List<@Valid OptionRequest> options,
                               List<Long> productIds) {
    }

    @Transactional
    public GroupDto saveGroup(Long id, GroupRequest r) {
        Long t = tenant();
        CurrentUser.require(Permission.PRODUCTOS_EDITAR);
        ModifierGroup g = id == null ? new ModifierGroup() : groups.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Grupo no encontrado"));
        int min = r.minSelect() == null ? 0 : r.minSelect();
        int max = r.maxSelect() == null ? 1 : r.maxSelect();
        if (max > 0 && min > max) throw ApiException.field("minSelect", "el mínimo no puede ser mayor que el máximo");
        if (min > r.options().size()) throw ApiException.field("minSelect", "hay menos opciones que el mínimo");
        g.tenantId = t;
        g.name = r.name().trim();
        g.minSelect = min;
        g.maxSelect = max;
        g = groups.save(g);
        // Opciones: se actualizan las que vienen, se desactivan las que no.
        Map<Long, ModifierOption> current = new HashMap<>();
        for (ModifierOption o : options.findByGroupIdOrderBySortOrderAscIdAsc(g.id)) current.put(o.id, o);
        Set<Long> keep = new HashSet<>();
        int order = 0;
        for (OptionRequest or : r.options()) {
            ModifierOption o = or.id() != null && current.containsKey(or.id()) ? current.get(or.id()) : new ModifierOption();
            o.tenantId = t;
            o.groupId = g.id;
            o.name = or.name().trim();
            o.price = or.price() == null ? BigDecimal.ZERO : or.price().setScale(2, RoundingMode.HALF_UP);
            o.sortOrder = order++;
            o.active = true;
            o = options.save(o);
            keep.add(o.id);
        }
        for (ModifierOption o : current.values()) if (!keep.contains(o.id)) o.active = false;
        if (r.productIds() != null) {
            em.createNativeQuery("delete from product_modifier_groups where group_id = :g").setParameter("g", g.id).executeUpdate();
            for (Long pid : new LinkedHashSet<>(r.productIds())) {
                products.findByIdAndTenantId(pid, t).orElseThrow(() -> ApiException.field("productIds", "un producto no existe"));
                em.createNativeQuery("insert into product_modifier_groups (product_id, group_id, sort_order) values (:p, :g, :o)")
                        .setParameter("p", pid).setParameter("g", g.id).setParameter("o", g.sortOrder).executeUpdate();
            }
        }
        audit.log("MODIFICADORES", "MODIFIER_GROUP", g.id, "Guardó el grupo de modificadores \"" + g.name + "\" (" + keep.size() + " opciones)");
        final Long gid = g.id;
        return modifiers().stream().filter(x -> x.id().equals(gid)).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteGroup(Long id) {
        Long t = tenant();
        CurrentUser.require(Permission.PRODUCTOS_EDITAR);
        ModifierGroup g = groups.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Grupo no encontrado"));
        g.active = false;
        em.createNativeQuery("delete from product_modifier_groups where group_id = :g").setParameter("g", g.id).executeUpdate();
    }

    // =====================================================================
    // Estaciones (cocina / bar) por categoría y producto
    // =====================================================================

    public record StationCategoryDto(Long id, String name, String station) {
    }

    @Transactional(readOnly = true)
    public List<StationCategoryDto> stations() {
        Long t = tenant();
        return categories.findByTenantIdOrderBySortOrderAscNameAsc(t).stream()
                .map(c -> new StationCategoryDto(c.id, c.name, c.station)).toList();
    }

    public record StationsRequest(Map<Long, String> categories) {
    }

    @Transactional
    public List<StationCategoryDto> saveStations(StationsRequest r) {
        Long t = tenant();
        CurrentUser.require(Permission.CONFIG_EDITAR);
        if (r.categories() != null) {
            for (Map.Entry<Long, String> e : r.categories().entrySet()) {
                Category c = categories.findByIdAndTenantId(e.getKey(), t).orElseThrow(() -> ApiException.notFound("Categoría no encontrada"));
                c.station = normalizeStation(e.getValue());
            }
        }
        return stations();
    }

    public static String normalizeStation(String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim().toUpperCase();
        if (!KitchenService.STATIONS.contains(s)) throw ApiException.field("station", "elige Cocina o Bar");
        return s;
    }

    @Transactional
    public void saveTipSettings(boolean enabled, BigDecimal pct) {
        Long t = tenant();
        CurrentUser.require(Permission.CONFIG_EDITAR);
        StoreSettings st = settings.forTenant(t);
        st.tipsEnabled = enabled;
        st.tipSuggestedPct = pct == null ? BigDecimal.TEN : pct.setScale(2, RoundingMode.HALF_UP);
        em.merge(st);
        audit.log("PROPINAS", "SETTINGS", t, "Propina voluntaria " + (enabled ? "sugerida al " + st.tipSuggestedPct.stripTrailingZeros().toPlainString() + " %" : "desactivada"));
    }

    // =====================================================================
    // Cobro con propina voluntaria
    // =====================================================================

    public record TipRequest(@DecimalMin(value = "0", message = "no puede ser negativa") BigDecimal amount, String method) {
    }

    public record CheckoutRequest(@NotNull @Valid AccountCheckoutRequest checkout, @Valid TipRequest tip) {
    }

    /**
     * Cobra la cuenta y registra la propina voluntaria aparte del total (no es venta ni lleva impuestos). La propina en
     * efectivo entra a la gaveta y se suma al esperado del turno.
     */
    @Transactional
    public SaleDto checkout(Long accountId, CheckoutRequest r) {
        Long t = tenant();
        StoreSettings st = settings.forTenant(t);
        BigDecimal tip = r.tip() == null || r.tip().amount() == null ? BigDecimal.ZERO : r.tip().amount().setScale(2, RoundingMode.HALF_UP);
        String method = null;
        if (tip.signum() > 0) {
            if (!st.tipsEnabled) throw ApiException.field("tip", "la tienda no recibe propinas");
            method = r.tip().method() == null ? "EFECTIVO" : r.tip().method().trim().toUpperCase();
            if ("FIADO".equals(method) || !st.paymentMethodList().contains(method)) throw ApiException.field("tip", "medio de la propina no válido");
        }
        SaleDto dto = saleService.checkoutAccount(accountId, r.checkout());
        if (tip.signum() > 0) {
            Sale s = sales.findById(dto.id()).orElseThrow();
            s.tipAmount = tip;
            s.tipMethod = method;
            sales.save(s);
            audit.log("PROPINA", "SALE", s.id, "Propina voluntaria de " + Money.fmt(tip) + " por " + method.toLowerCase() + " en la venta #" + s.number);
            return saleService.get(s.id);
        }
        return dto;
    }
}
