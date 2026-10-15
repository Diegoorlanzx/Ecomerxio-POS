package com.tiendaposs.users;

import com.tiendaposs.security.Permission;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.stream.Collectors;

/** Crea en una tienda nueva los 8 roles iniciales. */
@Component
public class RoleSeeder {

    private final RoleRepository roles;

    public RoleSeeder(RoleRepository roles) {
        this.roles = roles;
    }

    public void seed(Long tenantId) {
        for (SystemRole sr : SystemRole.values()) {
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
}
