package com.tiendaposs.users;

import com.tiendaposs.security.Permission;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.stream.Collectors;

/**
 * Crea los roles predefinidos de una tienda: los generales al registrarla y los de un módulo (mesero y cocina del
 * restaurante) cuando la tienda tiene ese módulo encendido.
 */
@Component
public class RoleSeeder {

    private final RoleRepository roles;

    public RoleSeeder(RoleRepository roles) {
        this.roles = roles;
    }

    /** Roles generales de una tienda nueva. */
    public void seed(Long tenantId) {
        for (SystemRole sr : SystemRole.values()) {
            if (sr.module() == null) create(tenantId, sr);
        }
    }

    /**
     * Crea los roles de los módulos encendidos que la tienda todavía no tiene. Si el dueño ya hizo un rol propio con el
     * mismo nombre (p. ej. "Mesero"), se respeta el suyo y no se crea otro.
     */
    public void ensureModuleRoles(Long tenantId, Collection<String> modules) {
        var existing = roles.findByTenantIdOrderByIdAsc(tenantId);
        for (SystemRole sr : SystemRole.values()) {
            if (sr.module() == null || !modules.contains(sr.module())) continue;
            boolean has = existing.stream().anyMatch(r -> r.getSystemCode() == sr || r.getName().equalsIgnoreCase(sr.displayName()));
            if (!has) create(tenantId, sr);
        }
    }

    private void create(Long tenantId, SystemRole sr) {
        Role r = new Role();
        r.setTenantId(tenantId);
        r.setSystemCode(sr);
        r.setName(sr.displayName());
        r.setDescription(sr.description());
        r.setPermissions(sr.permissions().stream().map(Permission::name)
                .collect(Collectors.toCollection(HashSet::new)));
        roles.save(r);
    }
}
