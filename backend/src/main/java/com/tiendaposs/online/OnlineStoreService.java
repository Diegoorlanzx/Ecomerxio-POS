package com.tiendaposs.online;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogRepos.*;
import com.tiendaposs.catalog.ComboComponent;
import com.tiendaposs.catalog.Presentation;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.online.OnlineRepos.*;
import com.tiendaposs.payments.PaymentRepos.PaymentAccountRepository;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.ModulesService;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.settings.StoreSettings;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.tenant.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.*;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

/** Vitrina en línea: configuración, qué se publica y el catálogo público que ve el cliente. */
@Service
public class OnlineStoreService {

    public static final Map<String, String> PAY_LABELS = new LinkedHashMap<>();

    static {
        PAY_LABELS.put("CONTRA_ENTREGA", "Pago al recibir (efectivo o tarjeta)");
        PAY_LABELS.put("NEQUI", "Nequi");
        PAY_LABELS.put("DAVIPLATA", "Daviplata");
        PAY_LABELS.put("BREB", "Bre-B / llave");
        PAY_LABELS.put("TRANSFERENCIA", "Transferencia bancaria");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OnlineStoreRepository stores;
    private final DeliveryZoneRepository zones;
    private final ProductRepository products;
    private final PresentationRepository presentations;
    private final CategoryRepository categories;
    private final ComboComponentRepository combos;
    private final PaymentAccountRepository accounts;
    private final TenantRepository tenants;
    private final SettingsService settings;
    private final AuditService audit;

    public OnlineStoreService(OnlineStoreRepository stores, DeliveryZoneRepository zones, ProductRepository products,
                              PresentationRepository presentations, CategoryRepository categories, ComboComponentRepository combos,
                              PaymentAccountRepository accounts, TenantRepository tenants, SettingsService settings, AuditService audit) {
        this.stores = stores;
        this.zones = zones;
        this.products = products;
        this.presentations = presentations;
        this.categories = categories;
        this.combos = combos;
        this.accounts = accounts;
        this.tenants = tenants;
        this.settings = settings;
        this.audit = audit;
    }

    // =====================================================================
    // Configuración (personal de la tienda)
    // =====================================================================

    public record DaySchedule(@Min(1) @Max(7) int day, @Pattern(regexp = "\\d{2}:\\d{2}", message = "hora no válida") String open,
                              @Pattern(regexp = "\\d{2}:\\d{2}", message = "hora no válida") String close, boolean closed) {
    }

    public record ZoneDto(Long id, @NotBlank @Size(max = 80) String name,
                          @NotNull @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal fee,
                          @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal minOrder, boolean active) {
    }

    public record ConfigDto(boolean enabled, boolean moduleOn, String slug, String mode, String welcome, String whatsapp, boolean showStock,
                            boolean deliveryEnabled, boolean pickupEnabled, BigDecimal minOrder, List<String> payMethods,
                            Map<String, String> payLabels, List<DaySchedule> schedule, boolean paused, String pausedMessage,
                            int prepMinutes, boolean showBadge, List<ZoneDto> zones, long publishedProducts, boolean openNow, String statusText) {
    }

    public record ConfigRequest(boolean enabled,
                                @NotBlank @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*", message = "solo minúsculas, números y guiones") @Size(min = 3, max = 60) String slug,
                                @NotBlank @Pattern(regexp = "PEDIDOS|CARTA") String mode,
                                @Size(max = 300) String welcome, @Size(max = 30) String whatsapp, boolean showStock,
                                boolean deliveryEnabled, boolean pickupEnabled,
                                @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal minOrder,
                                @NotNull List<String> payMethods, List<@Valid DaySchedule> schedule, boolean paused,
                                @Size(max = 200) String pausedMessage,
                                @Min(value = 5, message = "mínimo 5 minutos") @Max(value = 600, message = "máximo 600") int prepMinutes,
                                boolean showBadge, List<@Valid ZoneDto> zones) {
    }

    @Transactional
    public OnlineStore storeOf(Long t) {
        return stores.findById(t).orElseGet(() -> {
            OnlineStore s = new OnlineStore();
            s.tenantId = t;
            Tenant tenant = tenants.findById(t).orElseThrow();
            s.slug = uniqueSlug(slugify(tenant.getName()), t);
            s.whatsapp = tenant.getWhatsapp();
            s.schedule = writeJson(defaultSchedule());
            return stores.save(s);
        });
    }

    @Transactional
    public ConfigDto config() {
        Long t = TenantContext.require();
        return toConfig(storeOf(t));
    }

    @Transactional
    public ConfigDto update(ConfigRequest r) {
        Long t = TenantContext.require();
        StoreSettings st = settings.forTenant(t);
        if (r.enabled()) ModulesService.check(st, "TIENDA_ONLINE");
        OnlineStore s = storeOf(t);
        if (stores.slugTaken(r.slug(), t)) throw ApiException.field("slug", "esa dirección ya la usa otra tienda");
        for (String m : r.payMethods()) {
            if (!PAY_LABELS.containsKey(m)) throw ApiException.field("payMethods", "forma de pago no válida: " + m);
        }
        if (r.mode().equals("PEDIDOS") && r.payMethods().isEmpty()) throw ApiException.field("payMethods", "elige al menos una forma de pago");
        if (r.mode().equals("PEDIDOS") && !r.deliveryEnabled() && !r.pickupEnabled()) {
            throw ApiException.field("deliveryEnabled", "activa el domicilio, recoger en tienda o ambos");
        }
        boolean wasEnabled = s.enabled;
        s.enabled = r.enabled();
        s.slug = r.slug();
        s.mode = r.mode();
        s.welcome = blank(r.welcome());
        s.whatsapp = blank(r.whatsapp());
        s.showStock = r.showStock();
        s.deliveryEnabled = r.deliveryEnabled();
        s.pickupEnabled = r.pickupEnabled();
        s.minOrder = r.minOrder() == null ? BigDecimal.ZERO : r.minOrder();
        s.payMethods = String.join(",", r.payMethods());
        if (r.schedule() != null) {
            for (DaySchedule d : r.schedule()) {
                if (!d.closed() && d.open().compareTo(d.close()) >= 0) {
                    throw ApiException.field("schedule", "el " + dayName(d.day()) + " la hora de cierre debe ser después de la de apertura");
                }
            }
            s.schedule = r.schedule().isEmpty() ? null : writeJson(r.schedule());
        }
        s.paused = r.paused();
        s.pausedMessage = blank(r.pausedMessage());
        s.prepMinutes = r.prepMinutes();
        s.showBadge = r.showBadge();
        if (r.zones() != null) saveZones(t, r.zones());
        audit.log("TIENDA_ONLINE", "TENANT", t, (s.enabled ? (wasEnabled ? "Actualizó" : "Publicó") : "Apagó/actualizó")
                + " la tienda en línea /t/" + s.slug + (s.paused ? " (en pausa)" : ""));
        return toConfig(s);
    }

    private void saveZones(Long t, List<ZoneDto> reqs) {
        Map<Long, DeliveryZone> existing = zones.findByTenantIdOrderBySortOrderAscIdAsc(t).stream().collect(Collectors.toMap(z -> z.id, z -> z));
        Set<Long> kept = new HashSet<>();
        int order = 0;
        for (ZoneDto r : reqs) {
            DeliveryZone z = r.id() == null ? new DeliveryZone() : existing.get(r.id());
            if (z == null) throw ApiException.field("zones", "una zona no pertenece a la tienda");
            z.tenantId = t;
            z.name = r.name().trim();
            z.fee = r.fee();
            z.minOrder = r.minOrder() == null ? BigDecimal.ZERO : r.minOrder();
            z.active = r.active();
            z.sortOrder = order++;
            kept.add(zones.save(z).id);
        }
        // Las zonas que ya no vienen se desactivan (los pedidos viejos las referencian).
        existing.values().stream().filter(z -> !kept.contains(z.id)).forEach(z -> z.active = false);
    }

    private ConfigDto toConfig(OnlineStore s) {
        StoreSettings st = settings.forTenant(s.tenantId);
        long published = products.findByTenantIdAndStatusOrderByNameAsc(s.tenantId, "ACTIVE").stream()
                .filter(p -> p.onlineVisible && p.parentId == null && !p.trackSerials).count();
        ZoneId z = zone(s.tenantId);
        OpenState os = openState(s, z);
        return new ConfigDto(s.enabled, st.hasModule("TIENDA_ONLINE"), s.slug, s.mode, s.welcome, s.whatsapp, s.showStock,
                s.deliveryEnabled, s.pickupEnabled, s.minOrder, s.payMethodList(), PAY_LABELS, schedule(s), s.paused,
                s.pausedMessage, s.prepMinutes, s.showBadge,
                zones.findByTenantIdOrderBySortOrderAscIdAsc(s.tenantId).stream().filter(x -> x.active)
                        .map(x -> new ZoneDto(x.id, x.name, x.fee, x.minOrder, x.active)).toList(),
                published, os.open(), os.text());
    }

    // ---------------- Publicación de productos ----------------

    public record PublishItem(Long id, String name, String productType, Long parentId, String categoryName, boolean visible,
                              Long presentationId, BigDecimal price, BigDecimal onlinePrice, BigDecimal stock, boolean trackSerials) {
    }

    public record PublishRequest(@NotNull Long productId, Boolean visible, BigDecimal onlinePrice, boolean clearOnlinePrice) {
    }

    @Transactional(readOnly = true)
    public List<PublishItem> publishList() {
        Long t = TenantContext.require();
        Map<Long, String> cats = categories.findByTenantIdOrderBySortOrderAscNameAsc(t).stream().collect(Collectors.toMap(c -> c.id, c -> c.name));
        List<Product> ps = products.findByTenantIdAndStatusOrderByNameAsc(t, "ACTIVE").stream().filter(p -> !"DOMICILIO".equals(p.sku)).toList();
        Map<Long, Presentation> bases = presentations.findByTenantId(t).stream().filter(x -> x.isBase)
                .collect(Collectors.toMap(x -> x.productId, x -> x, (a, b) -> a));
        List<PublishItem> out = new ArrayList<>();
        for (Product p : ps) {
            Presentation b = bases.get(p.id);
            out.add(new PublishItem(p.id, p.name, p.productType, p.parentId, p.categoryId == null ? null : cats.get(p.categoryId),
                    p.onlineVisible, b == null ? null : b.id, b == null ? null : b.price, b == null ? null : b.onlinePrice,
                    p.stock.subtract(p.reserved), p.trackSerials));
        }
        return out;
    }

    @Transactional
    public int publish(List<PublishRequest> reqs) {
        Long t = TenantContext.require();
        int changed = 0;
        for (PublishRequest r : reqs) {
            Product p = products.findByIdAndTenantId(r.productId(), t).orElseThrow(() -> ApiException.notFound("Producto no encontrado"));
            if (r.visible() != null && r.visible() != p.onlineVisible) {
                CurrentUser.require(Permission.PRODUCTOS_EDITAR);
                p.onlineVisible = r.visible();
                changed++;
            }
            if (r.onlinePrice() != null || r.clearOnlinePrice()) {
                CurrentUser.require(Permission.PRODUCTOS_PRECIOS);
                Presentation b = presentations.findByProductIdOrderBySortOrderAscIdAsc(p.id).stream().filter(x -> x.isBase).findFirst().orElseThrow();
                BigDecimal np = r.clearOnlinePrice() ? null : r.onlinePrice().setScale(2, RoundingMode.HALF_UP);
                if (np != null && np.signum() < 0) throw ApiException.field("onlinePrice", "no puede ser negativo");
                if (!Objects.equals(b.onlinePrice == null ? null : b.onlinePrice.stripTrailingZeros(), np == null ? null : np.stripTrailingZeros())) {
                    b.onlinePrice = np;
                    changed++;
                }
            }
        }
        if (changed > 0) audit.log("TIENDA_ONLINE_PRODUCTOS", "TENANT", t, "Cambió " + changed + " publicaciones o precios de la tienda en línea");
        return changed;
    }

    // =====================================================================
    // Catálogo público (sin sesión)
    // =====================================================================

    public record PublicPresentation(Long id, String name, BigDecimal factor, BigDecimal price) {
    }

    public record PublicProduct(Long id, String name, String brand, String description, String photoUrl, Long categoryId, String unit,
                                String productType, Long parentId, String variantLabel, Map<String, String> variantValues,
                                List<Map<String, Object>> variantOptions, List<PublicPresentation> presentations,
                                boolean available, BigDecimal stock) {
    }

    public record PublicCategory(Long id, Long parentId, String name) {
    }

    public record PublicZone(Long id, String name, BigDecimal fee, BigDecimal minOrder) {
    }

    public record PublicPay(String code, String label) {
    }

    public record PublicStore(String slug, String name, String logoUrl, String address, String city, String phone, String whatsapp,
                              String welcome, String mode, boolean open, String statusText, int prepMinutes, boolean deliveryEnabled,
                              boolean pickupEnabled, BigDecimal minOrder, List<PublicZone> zones, List<PublicPay> payMethods,
                              boolean showBadge, String currency, List<PublicCategory> categories, List<PublicProduct> products) {
    }

    /** La tienda publicada por su dirección; 404 si no existe o no está activa. */
    @Transactional(readOnly = true)
    public OnlineStore published(String slug) {
        OnlineStore s = stores.findBySlug(slug == null ? "" : slug.trim()).orElseThrow(() -> ApiException.notFound("Esta tienda no tiene catálogo en línea"));
        if (!s.enabled || !settings.forTenant(s.tenantId).hasModule("TIENDA_ONLINE")) {
            throw ApiException.notFound("Esta tienda no tiene catálogo en línea");
        }
        return s;
    }

    @Transactional(readOnly = true)
    public PublicStore publicStore(String slug) {
        OnlineStore s = published(slug);
        Long t = s.tenantId;
        Tenant tenant = tenants.findById(t).orElseThrow();
        StoreSettings st = settings.forTenant(t);
        OpenState os = openState(s, zone(t));
        List<Product> all = products.findByTenantIdAndStatusOrderByNameAsc(t, "ACTIVE");
        Map<Long, Product> byId = all.stream().collect(Collectors.toMap(p -> p.id, p -> p));
        Set<Long> hiddenParents = all.stream().filter(p -> "PADRE".equals(p.productType) && !p.onlineVisible).map(p -> p.id).collect(Collectors.toSet());
        List<Product> list = all.stream().filter(p -> p.onlineVisible && !p.trackSerials && !"DOMICILIO".equals(p.sku)
                && (p.parentId == null || !hiddenParents.contains(p.parentId))).toList();
        Map<Long, List<Presentation>> pres = presentations.findByTenantId(t).stream().filter(x -> x.active)
                .sorted(Comparator.comparingInt((Presentation x) -> x.sortOrder).thenComparing(x -> x.id))
                .collect(Collectors.groupingBy(x -> x.productId));
        Map<Long, List<ComboComponent>> comps = combos.findByTenantId(t).stream().collect(Collectors.groupingBy(c -> c.comboId));
        List<PublicProduct> out = new ArrayList<>();
        for (Product p : list) {
            boolean parent = "PADRE".equals(p.productType);
            BigDecimal avail = available(p, byId, comps);
            List<PublicPresentation> pp = parent ? List.of() : pres.getOrDefault(p.id, List.of()).stream()
                    .map(x -> new PublicPresentation(x.id, x.name, x.factor, x.onlinePrice != null ? x.onlinePrice : x.price)).toList();
            if (!parent && pp.isEmpty()) continue;
            boolean ok = avail == null || avail.signum() > 0 || st.allowNegativeStock;
            String photo = p.photo == null && p.parentId != null && byId.get(p.parentId) != null && byId.get(p.parentId).photo != null
                    ? photoUrl(s.slug, p.parentId, byId.get(p.parentId)) : p.photo == null ? null : photoUrl(s.slug, p.id, p);
            out.add(new PublicProduct(p.id, p.name, p.brand, p.description, photo, p.categoryId, p.unit, p.productType, p.parentId,
                    p.variantLabel, readMap(p.variantValues), readList(p.variantOptions), pp, ok,
                    s.showStock && avail != null ? avail.max(BigDecimal.ZERO) : null));
        }
        var allCats = categories.findByTenantIdOrderBySortOrderAscNameAsc(t);
        Map<Long, Long> parentOf = new HashMap<>();
        allCats.forEach(c -> parentOf.put(c.id, c.parentId));
        Set<Long> usedCats = new HashSet<>();
        list.stream().map(p -> p.categoryId).filter(Objects::nonNull).forEach(id -> {
            usedCats.add(id);
            if (parentOf.get(id) != null) usedCats.add(parentOf.get(id));
        });
        List<PublicCategory> cats = allCats.stream().filter(c -> c.active && usedCats.contains(c.id))
                .map(c -> new PublicCategory(c.id, c.parentId, c.name)).toList();
        List<PublicPay> pays = s.payMethodList().stream().map(m -> new PublicPay(m, PAY_LABELS.getOrDefault(m, m))).toList();
        return new PublicStore(s.slug, tenant.getName(), tenant.getLogo() == null ? null : "/api/public/stores/" + s.slug + "/logo?v=" + tenant.getUpdatedAt().toEpochMilli(),
                tenant.getAddress(), tenant.getCity(), tenant.getPhone(), s.whatsapp, s.welcome, s.mode, os.open(), os.text(), s.prepMinutes,
                s.deliveryEnabled, s.pickupEnabled, s.minOrder,
                zones.findByTenantIdOrderBySortOrderAscIdAsc(t).stream().filter(z -> z.active).map(z -> new PublicZone(z.id, z.name, z.fee, z.minOrder)).toList(),
                pays, s.showBadge, tenant.getCurrency(), cats, out);
    }

    /** Unidades disponibles (stock − apartado). Combos: lo que alcanzan sus componentes. null = sin control de stock. */
    BigDecimal available(Product p, Map<Long, Product> byId, Map<Long, List<ComboComponent>> comps) {
        if ("PADRE".equals(p.productType)) {
            BigDecimal sum = BigDecimal.ZERO;
            boolean any = false;
            for (Product c : byId.values()) {
                if (!p.id.equals(c.parentId) || !c.onlineVisible) continue;
                BigDecimal a = available(c, byId, comps);
                if (a == null) return null;
                sum = sum.add(a.max(BigDecimal.ZERO));
                any = true;
            }
            return any ? sum : BigDecimal.ZERO;
        }
        if ("COMBO".equals(p.productType)) {
            BigDecimal min = null;
            for (ComboComponent c : comps.getOrDefault(p.id, List.of())) {
                Product x = byId.get(c.componentId);
                if (x == null) return BigDecimal.ZERO;
                if (!x.trackStock) continue;
                BigDecimal can = x.stock.subtract(x.reserved).divide(c.quantity, 0, RoundingMode.FLOOR);
                min = min == null ? can : min.min(can);
            }
            return min;
        }
        return p.trackStock ? p.stock.subtract(p.reserved) : null;
    }

    private static String photoUrl(String slug, Long id, Product p) {
        return "/api/public/stores/" + slug + "/photo/" + id + "?v=" + p.updatedAt.toEpochMilli();
    }

    public record Image(byte[] bytes, String contentType) {
    }

    @Transactional(readOnly = true)
    public Image photo(String slug, Long productId) {
        OnlineStore s = published(slug);
        Product p = products.findByIdAndTenantId(productId, s.tenantId).filter(x -> x.onlineVisible && x.photo != null)
                .orElseThrow(() -> ApiException.notFound("Sin foto"));
        return decode(p.photo);
    }

    @Transactional(readOnly = true)
    public Image logo(String slug) {
        OnlineStore s = published(slug);
        String logo = tenants.findById(s.tenantId).map(Tenant::getLogo).orElse(null);
        if (logo == null) throw ApiException.notFound("Sin logo");
        return decode(logo);
    }

    private static Image decode(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        String type = dataUrl.substring(5, dataUrl.indexOf(';'));
        return new Image(Base64.getDecoder().decode(dataUrl.substring(comma + 1)), type);
    }

    // =====================================================================
    // Horario
    // =====================================================================

    public record OpenState(boolean open, String text) {
    }

    public static List<DaySchedule> defaultSchedule() {
        List<DaySchedule> out = new ArrayList<>();
        for (int d = 1; d <= 7; d++) out.add(new DaySchedule(d, "08:00", d == 7 ? "14:00" : "20:00", false));
        return out;
    }

    public List<DaySchedule> schedule(OnlineStore s) {
        if (s.schedule == null || s.schedule.isBlank()) return List.of();
        try {
            return JSON.readValue(s.schedule, new TypeReference<List<DaySchedule>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** ¿Atiende ahora? Si no, cuándo abre ("Abre hoy a las 7:00 a. m."). Sin horario = siempre abierta. */
    public OpenState openState(OnlineStore s, ZoneId z) {
        if (s.paused) return new OpenState(false, s.pausedMessage == null ? "En este momento no estamos recibiendo pedidos" : s.pausedMessage);
        List<DaySchedule> sch = schedule(s);
        if (sch.isEmpty()) return new OpenState(true, "Abierto");
        ZonedDateTime now = ZonedDateTime.now(z);
        Map<Integer, DaySchedule> byDay = sch.stream().collect(Collectors.toMap(DaySchedule::day, d -> d, (a, b) -> a));
        DaySchedule today = byDay.get(now.getDayOfWeek().getValue());
        String hhmm = String.format("%02d:%02d", now.getHour(), now.getMinute());
        if (today != null && !today.closed() && hhmm.compareTo(today.open()) >= 0 && hhmm.compareTo(today.close()) < 0) {
            return new OpenState(true, "Abierto hasta las " + time12(today.close()));
        }
        for (int i = 0; i < 8; i++) {
            LocalDate d = now.toLocalDate().plusDays(i);
            DaySchedule ds = byDay.get(d.getDayOfWeek().getValue());
            if (ds == null || ds.closed()) continue;
            if (i == 0 && hhmm.compareTo(ds.open()) >= 0) continue;
            String when = i == 0 ? "hoy" : i == 1 ? "mañana" : "el " + dayName(d.getDayOfWeek().getValue());
            return new OpenState(false, "Cerrado. Abrimos " + when + " a las " + time12(ds.open()));
        }
        return new OpenState(false, "Cerrado por ahora");
    }

    static String time12(String hhmm) {
        int h = Integer.parseInt(hhmm.substring(0, 2));
        String m = hhmm.substring(3);
        String suf = h < 12 ? "a. m." : "p. m.";
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return h12 + ":" + m + " " + suf;
    }

    static String dayName(int day) {
        return DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.forLanguageTag("es-CO"));
    }

    ZoneId zone(Long t) {
        return ZoneId.of(tenants.findById(t).map(Tenant::getTimezone).orElse("America/Bogota"));
    }

    // ---------------- utilidades ----------------

    static String slugify(String name) {
        String s = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (s.length() < 3) s = "tienda" + (s.isEmpty() ? "" : "-" + s);
        return s.length() > 50 ? s.substring(0, 50).replaceAll("-+$", "") : s;
    }

    private String uniqueSlug(String base, Long t) {
        String s = base;
        int n = 2;
        while (stores.slugTaken(s, t)) s = base + "-" + n++;
        return s;
    }

    static String writeJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> readMap(String json) {
        if (json == null) return null;
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Map<String, Object>> readList(String json) {
        if (json == null) return null;
        try {
            return JSON.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
