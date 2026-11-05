package com.tiendaposs.restaurant;

import com.tiendaposs.catalog.CatalogRepos.CategoryRepository;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Category;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Counters;
import com.tiendaposs.pos.PosRepos.SaleLineRepository;
import com.tiendaposs.pos.PosRepos.SaleRepository;
import com.tiendaposs.pos.Sale;
import com.tiendaposs.pos.SaleLine;
import com.tiendaposs.restaurant.RestaurantRepos.KitchenTicketRepository;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Comandas a cocina y bar. Cada producto va a su estación (la del producto o la de su categoría); lo que no tiene
 * estación (una gaseosa que se sirve en la barra de la caja) no genera comanda. El mesero manda la comanda cuando
 * termina de pedir; solo se envía lo que falta por enviar de cada línea.
 */
@Service
public class KitchenService {

    public static final Set<String> STATIONS = Set.of("COCINA", "BAR");
    private static final List<String> FLOW = List.of("PENDIENTE", "PREPARANDO", "LISTO", "ENTREGADO");

    private final KitchenTicketRepository tickets;
    private final SaleRepository sales;
    private final SaleLineRepository lines;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final Counters counters;
    /** Lo registra la tienda en línea: avanza el pedido cuando la cocina lo empieza o lo termina. */
    private java.util.function.Consumer<Long> onlineListener;

    public void onOnlineTicketChanged(java.util.function.Consumer<Long> listener) {
        this.onlineListener = listener;
    }

    public KitchenService(KitchenTicketRepository tickets, SaleRepository sales, SaleLineRepository lines, ProductRepository products,
                          CategoryRepository categories, Counters counters) {
        this.tickets = tickets;
        this.sales = sales;
        this.lines = lines;
        this.products = products;
        this.categories = categories;
        this.counters = counters;
    }

    public record TicketDto(Long id, Long saleId, int comanda, String station, String tableLabel, String waiterName, String productName,
                            BigDecimal quantity, String modifiers, String note, String kind, String status, Instant createdAt,
                            Instant startedAt, Instant readyAt, Instant deliveredAt, long minutes, Long onlineOrderId) {
    }

    public record SendResult(int comanda, int sent, List<String> stations) {
    }

    /** Estación de un producto: la propia o la de su categoría (o null si no se prepara). */
    String stationOf(Product p, Map<Long, Category> cats) {
        if (p.station != null) return p.station;
        Category c = p.categoryId == null ? null : cats.get(p.categoryId);
        return c == null ? null : c.station;
    }

    /** Manda a cocina/bar lo que falta de cada línea de la cuenta. */
    @Transactional
    public SendResult send(Long saleId) {
        Long t = TenantContext.require();
        Sale s = sales.lockById(saleId, t).orElseThrow(() -> ApiException.notFound("La cuenta ya no existe"));
        if (!"ABIERTA".equals(s.status)) throw ApiException.conflict("Esa cuenta ya no está abierta");
        com.tiendaposs.pos.AccountAccess.check(s);
        Map<Long, Category> cats = new HashMap<>();
        categories.findByTenantIdOrderBySortOrderAscNameAsc(t).forEach(c -> cats.put(c.id, c));
        int comanda = 0;
        int sent = 0;
        Set<String> stations = new TreeSet<>();
        for (SaleLine l : lines.findBySaleIdOrderBySortOrderAscIdAsc(s.id)) {
            BigDecimal diff = l.quantity.subtract(l.sentQty);
            if (diff.signum() == 0) continue;
            Product p = products.findById(l.productId).orElseThrow();
            String station = stationOf(p, cats);
            l.sentQty = l.quantity;
            lines.save(l);
            if (station == null) continue;
            if (comanda == 0) comanda = (int) counters.next(t, "COMANDA");
            KitchenTicket k = ticket(s, l, comanda, station, diff.abs());
            if (diff.signum() < 0) k.kind = "ANULAR";
            tickets.save(k);
            stations.add(station);
            sent++;
        }
        return new SendResult(comanda, sent, List.copyOf(stations));
    }

    /** Una línea de un pedido en línea para la cocina (domicilio o para llevar). */
    public record OnlineItem(Long productId, String productName, String presentationName, BigDecimal quantity, String modifiers, String note) {
    }

    /**
     * Comanda de un pedido en línea aceptado (domicilio o para llevar): va a la cocina como la de una mesa, con el
     * número del pedido. Cuando la cocina lo marca listo, el pedido queda listo para despachar.
     */
    @Transactional
    public SendResult sendOnline(Long tenantId, Long orderId, String label, String handledBy, List<OnlineItem> items) {
        Map<Long, Category> cats = new HashMap<>();
        categories.findByTenantIdOrderBySortOrderAscNameAsc(tenantId).forEach(c -> cats.put(c.id, c));
        int comanda = 0;
        int sent = 0;
        Set<String> stations = new TreeSet<>();
        for (OnlineItem it : items) {
            Product p = products.findById(it.productId()).orElse(null);
            if (p == null) continue;
            String station = stationOf(p, cats);
            if (station == null) continue;
            if (comanda == 0) comanda = (int) counters.next(tenantId, "COMANDA");
            KitchenTicket k = new KitchenTicket();
            k.tenantId = tenantId;
            k.onlineOrderId = orderId;
            k.comanda = comanda;
            k.station = station;
            k.tableLabel = label;
            k.waiterName = handledBy;
            k.productName = it.productName() + (it.presentationName() == null || "Unidad".equalsIgnoreCase(it.presentationName()) ? "" : " · " + it.presentationName());
            k.quantity = it.quantity().setScale(3, RoundingMode.HALF_UP);
            k.modifiers = it.modifiers();
            k.note = it.note();
            tickets.save(k);
            stations.add(station);
            sent++;
        }
        return new SendResult(comanda, sent, List.copyOf(stations));
    }

    /** Se canceló el pedido en línea: lo que la cocina no ha terminado pasa a "ANULAR". */
    @Transactional
    public void onOnlineCancelled(Long orderId) {
        for (KitchenTicket k : tickets.findByOnlineOrderIdOrderByIdAsc(orderId)) {
            if ("PENDIENTE".equals(k.status) || "PREPARANDO".equals(k.status)) {
                k.kind = "ANULAR";
                k.status = "PENDIENTE";
                k.startedAt = null;
            }
        }
    }

    /** Se entregó el pedido en línea: lo listo sale del tablero. */
    @Transactional
    public void onOnlineDelivered(Long orderId) {
        Instant now = Instant.now();
        for (KitchenTicket k : tickets.findByOnlineOrderIdOrderByIdAsc(orderId)) {
            if ("ANULAR".equals(k.kind) || "ENTREGADO".equals(k.status)) continue;
            k.status = "ENTREGADO";
            if (k.readyAt == null) k.readyAt = now;
            k.deliveredAt = now;
        }
    }

    /**
     * Estado de la cocina para un pedido en línea: "PREPARANDO" si empezó, "LISTO" si todo está listo, null si nada
     * ha empezado (o no tiene comandas).
     */
    public String onlineKitchenState(Long orderId) {
        List<KitchenTicket> ks = tickets.findByOnlineOrderIdOrderByIdAsc(orderId).stream().filter(k -> !"ANULAR".equals(k.kind)).toList();
        if (ks.isEmpty()) return null;
        boolean allReady = ks.stream().allMatch(k -> "LISTO".equals(k.status) || "ENTREGADO".equals(k.status));
        if (allReady) return "LISTO";
        boolean started = ks.stream().anyMatch(k -> !"PENDIENTE".equals(k.status));
        return started ? "PREPARANDO" : null;
    }

    /** El mesero quitó una línea que ya estaba en la cocina: la cocina recibe un "ANULAR". */
    public void onLineRemoved(Sale s, SaleLine l) {
        Product p = products.findById(l.productId).orElse(null);
        if (p == null) return;
        Map<Long, Category> cats = new HashMap<>();
        if (p.categoryId != null) categories.findById(p.categoryId).ifPresent(c -> cats.put(c.id, c));
        String station = stationOf(p, cats);
        if (station == null) return;
        KitchenTicket k = ticket(s, l, (int) counters.next(s.tenantId, "COMANDA"), station, l.sentQty);
        k.kind = "ANULAR";
        k.saleLineId = null;
        tickets.save(k);
    }

    /** Al unir dos mesas, lo que estaba en la cocina queda a nombre de la mesa que sigue. */
    public void onAccountMerged(Sale from, Sale into) {
        tickets.moveToSale(from.id, into.id, into.accountLabel);
    }

    private KitchenTicket ticket(Sale s, SaleLine l, int comanda, String station, BigDecimal qty) {
        KitchenTicket k = new KitchenTicket();
        k.tenantId = s.tenantId;
        k.saleId = s.id;
        k.saleLineId = l.id;
        k.comanda = comanda;
        k.station = station;
        k.tableLabel = s.accountLabel;
        k.waiterName = s.userName;
        k.productName = l.productName + (l.presentationName == null || "Unidad".equalsIgnoreCase(l.presentationName) ? "" : " · " + l.presentationName);
        k.quantity = qty.setScale(3, RoundingMode.HALF_UP);
        k.modifiers = l.modifiers;
        k.note = l.kitchenNote;
        return k;
    }

    /** Tablero de una estación (o de todas): lo pendiente, en preparación y listo, más lo entregado hace poco. */
    @Transactional(readOnly = true)
    public List<TicketDto> board(String station) {
        Long t = TenantContext.require();
        Instant now = Instant.now();
        return tickets.board(t, now.minus(Duration.ofMinutes(15))).stream()
                .filter(k -> station == null || station.isBlank() || station.equalsIgnoreCase(k.station))
                .map(k -> toDto(k, now)).toList();
    }

    /** Avanza (o regresa) el estado de una comanda. */
    @Transactional
    public TicketDto setStatus(Long id, String status) {
        Long t = TenantContext.require();
        KitchenTicket k = tickets.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Comanda no encontrada"));
        String st = status == null ? "" : status.trim().toUpperCase();
        if (!FLOW.contains(st)) throw ApiException.field("status", "estado no válido");
        Instant now = Instant.now();
        k.status = st;
        if (st.equals("PENDIENTE")) k.startedAt = k.readyAt = k.deliveredAt = null;
        if (st.equals("PREPARANDO")) {
            if (k.startedAt == null) k.startedAt = now;
            k.readyAt = k.deliveredAt = null;
        }
        if (st.equals("LISTO")) {
            if (k.startedAt == null) k.startedAt = now;
            k.readyAt = now;
            k.deliveredAt = null;
        }
        if (st.equals("ENTREGADO")) {
            if (k.readyAt == null) k.readyAt = now;
            k.deliveredAt = now;
        }
        if (k.onlineOrderId != null && onlineListener != null) {
            tickets.flush();
            onlineListener.accept(k.onlineOrderId);
        }
        return toDto(k, now);
    }

    /** Lo listo de una mesa se entrega de una vez (el mesero lo recoge). */
    @Transactional
    public int deliverReady(Long saleId) {
        Long t = TenantContext.require();
        sales.findById(saleId).filter(s -> t.equals(s.tenantId)).ifPresent(com.tiendaposs.pos.AccountAccess::check);
        int n = 0;
        for (KitchenTicket k : tickets.findBySaleIdOrderByIdAsc(saleId)) {
            if (!t.equals(k.tenantId) || !"LISTO".equals(k.status)) continue;
            k.status = "ENTREGADO";
            k.deliveredAt = Instant.now();
            n++;
        }
        return n;
    }

    /** Cuántas comandas de la cuenta están listas para llevar y cuántas siguen en cocina. */
    public int[] counts(Long saleId) {
        int ready = 0, cooking = 0;
        for (KitchenTicket k : tickets.findBySaleIdOrderByIdAsc(saleId)) {
            if ("ANULAR".equals(k.kind)) continue;
            if ("LISTO".equals(k.status)) ready++;
            else if ("PENDIENTE".equals(k.status) || "PREPARANDO".equals(k.status)) cooking++;
        }
        return new int[]{ready, cooking};
    }

    private TicketDto toDto(KitchenTicket k, Instant now) {
        return new TicketDto(k.id, k.saleId, k.comanda, k.station, k.tableLabel, k.waiterName, k.productName, k.quantity, k.modifiers,
                k.note, k.kind, k.status, k.createdAt, k.startedAt, k.readyAt, k.deliveredAt, Duration.between(k.createdAt, now).toMinutes(),
                k.onlineOrderId);
    }
}
