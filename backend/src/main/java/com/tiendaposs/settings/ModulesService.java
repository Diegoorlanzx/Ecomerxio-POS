package com.tiendaposs.settings;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Perfiles de negocio y módulos activables. El perfil solo propone qué módulos encender; la tienda puede
 * prender o apagar cada uno después. Los módulos apagados se ocultan en la app y el servidor los rechaza.
 */
@Service
public class ModulesService {

    public record ModuleInfo(String code, String name, String description, boolean available) {
    }

    public record Profile(String code, String name, String description, String icon, List<String> modules) {
    }

    public static final List<ModuleInfo> MODULES = List.of(
            new ModuleInfo("VARIANTES", "Variantes", "Tallas, colores, sabores o referencias de un mismo producto, cada una con su código, precio y stock.", true),
            new ModuleInfo("COMBOS", "Combos y kits", "Un producto que al venderse descuenta varios del inventario (desayuno, kit escolar, ancheta).", true),
            new ModuleInfo("SERIALES", "Seriales e IMEI", "Cada unidad con su número de serie: a quién se vendió y hasta cuándo tiene garantía.", true),
            new ModuleInfo("BASCULA", "Báscula y etiquetas de peso", "Lee etiquetas de balanza (código que empieza por 2), pesa con báscula conectada e imprime etiquetas.", true),
            new ModuleInfo("LOTES", "Lotes y vencimientos", "Controla fechas de vencimiento y vende primero lo que vence antes.", true),
            new ModuleInfo("FIADO", "Fiado (crédito a clientes)", "Ventas a crédito con cupo, plazo y abonos.", true),
            new ModuleInfo("CUENTAS_ABIERTAS", "Cuentas abiertas", "Mesas o cuentas que se van llenando y se cobran al final.", true),
            new ModuleInfo("COMPRAS", "Compras y proveedores", "Órdenes de compra, recepción de mercancía y cuentas por pagar.", true),
            new ModuleInfo("TIENDA_ONLINE", "Tienda en línea y catálogo QR", "Vitrina en internet con QR: el cliente pide desde el celular y el pedido llega a la caja.", true),
            new ModuleInfo("RESTAURANTE", "Restaurante: mesas y cocina", "Plano de mesas, meseros, modificadores (sin cebolla, término), comandas a cocina y bar, recetas, dividir la cuenta y propina voluntaria.", true),
            new ModuleInfo("SERVICIOS", "Servicios y agenda", "Próximamente (Fase 11).", false),
            new ModuleInfo("DISTRIBUCION", "Distribución y preventa", "Próximamente (Fase 12).", false));

    public static final List<Profile> PROFILES = List.of(
            new Profile("TIENDA", "Tienda de barrio o minimercado", "Abarrotes, bebidas, fiado y cuentas de mesa.", "storefront",
                    List.of("LOTES", "FIADO", "CUENTAS_ABIERTAS", "COMPRAS", "COMBOS")),
            new Profile("MODA", "Ropa, calzado y accesorios", "Tallas y colores por referencia, combos y cambios.", "checkroom",
                    List.of("VARIANTES", "COMBOS", "COMPRAS")),
            new Profile("TECNOLOGIA", "Celulares, tecnología y electrodomésticos", "Seriales e IMEI con garantía, variantes de color o capacidad.", "smartphone",
                    List.of("SERIALES", "VARIANTES", "COMBOS", "COMPRAS")),
            new Profile("FRUVER_CARNICERIA", "Fruver, carnicería o panadería", "Venta por peso con báscula y etiquetas, vencimientos.", "scale",
                    List.of("BASCULA", "LOTES", "FIADO", "COMPRAS")),
            new Profile("RESTAURANTE", "Restaurante, cafetería o bar", "Mesas, comandas a cocina, recetas y propina.", "restaurant",
                    List.of("RESTAURANTE", "CUENTAS_ABIERTAS", "COMBOS", "COMPRAS")),
            new Profile("SERVICIOS", "Servicios (peluquería, taller, spa)", "Servicios sin inventario y productos de venta.", "content_cut",
                    List.of("COMBOS", "FIADO")),
            new Profile("DISTRIBUIDORA", "Distribuidora o mayorista", "Compras grandes, lotes y crédito a clientes.", "local_shipping",
                    List.of("COMPRAS", "LOTES", "FIADO")),
            new Profile("MIXTO", "Varios tipos a la vez", "Todo encendido; apaga lo que no uses.", "category",
                    List.of("VARIANTES", "COMBOS", "SERIALES", "BASCULA", "LOTES", "FIADO", "CUENTAS_ABIERTAS", "COMPRAS", "TIENDA_ONLINE",
                            "RESTAURANTE")));

    private static final Set<String> ACTIVE_CODES = new HashSet<>(MODULES.stream().filter(ModuleInfo::available).map(ModuleInfo::code).toList());

    private final SettingsService settings;
    private final AuditService audit;

    public ModulesService(SettingsService settings, AuditService audit) {
        this.settings = settings;
        this.audit = audit;
    }

    public record ModulesDto(String businessType, List<String> enabled, List<ModuleInfo> modules, List<Profile> profiles,
                             String scalePrefixes, String scaleMode, int scalePluDigits) {
    }

    public record ModulesRequest(
            @NotBlank String businessType,
            @NotNull List<String> modules,
            @Size(max = 60) String scalePrefixes,
            @Pattern(regexp = "PESO|PRECIO", message = "modo no válido") String scaleMode,
            @Min(value = 4, message = "mínimo 4") @Max(value = 6, message = "máximo 6") Integer scalePluDigits) {
    }

    public ModulesDto get() {
        StoreSettings s = settings.current();
        return new ModulesDto(s.businessType, s.moduleList(), MODULES, PROFILES, s.scalePrefixes, s.scaleMode, s.scalePluDigits);
    }

    public static Profile profile(String code) {
        return PROFILES.stream().filter(p -> p.code().equals(code)).findFirst()
                .orElseThrow(() -> ApiException.field("businessType", "tipo de negocio no válido"));
    }

    @Transactional
    public ModulesDto update(ModulesRequest r) {
        Profile p = profile(r.businessType());
        for (String m : r.modules()) {
            if (!ACTIVE_CODES.contains(m)) throw ApiException.field("modules", "módulo no disponible: " + m);
        }
        StoreSettings s = settings.current();
        List<String> before = s.moduleList();
        apply(s, p.code(), r.modules());
        if (r.scalePrefixes() != null) {
            String prefixes = r.scalePrefixes().replaceAll("\\s", "");
            if (!prefixes.matches("2\\d(,2\\d)*")) throw ApiException.field("scalePrefixes", "escribe prefijos de 20 a 29 separados por coma");
            s.scalePrefixes = prefixes;
        }
        if (r.scaleMode() != null) s.scaleMode = r.scaleMode();
        if (r.scalePluDigits() != null) s.scalePluDigits = r.scalePluDigits();
        List<String> after = s.moduleList();
        List<String> on = after.stream().filter(x -> !before.contains(x)).toList();
        List<String> off = before.stream().filter(x -> !after.contains(x)).toList();
        audit.sensitive("CONFIG_MODULOS", "TENANT", s.tenantId, "Tipo de negocio: " + p.name()
                + (on.isEmpty() ? "" : ". Activó: " + String.join(", ", on))
                + (off.isEmpty() ? "" : ". Apagó: " + String.join(", ", off)));
        return get();
    }

    /** Aplica el perfil y los módulos (sin auditoría; también lo usa el registro de la tienda). */
    public static void apply(StoreSettings s, String businessType, List<String> modules) {
        s.businessType = businessType;
        boolean fiado = modules.contains("FIADO");
        s.modules = String.join(",", new LinkedHashSet<>(modules.stream().filter(m -> !"FIADO".equals(m) && ACTIVE_CODES.contains(m)).toList()));
        if (s.creditEnabled != fiado) {
            s.creditEnabled = fiado;
            List<String> methods = new ArrayList<>(s.paymentMethodList());
            if (fiado && !methods.contains("FIADO")) methods.add("FIADO");
            if (!fiado) methods.remove("FIADO");
            s.paymentMethods = String.join(",", methods);
        }
        s.updatedAt = Instant.now();
    }

    /** Lanza un error claro si el módulo está apagado. */
    public void require(String module) {
        check(settings.current(), module);
    }

    public static void check(StoreSettings s, String module) {
        if (!s.hasModule(module)) {
            String name = MODULES.stream().filter(m -> m.code().equals(module)).map(ModuleInfo::name).findFirst().orElse(module);
            throw ApiException.conflict("El módulo \"" + name + "\" está apagado. Actívalo en Más → Tipo de negocio y módulos.");
        }
    }
}
