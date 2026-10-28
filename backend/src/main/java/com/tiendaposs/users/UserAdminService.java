package com.tiendaposs.users;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.auth.Passwords;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.users.UserDtos.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Administración de empleados y roles de una tienda.
 * Regla anti-escalada: quien no es dueño no puede dar (a un rol o a un usuario) permisos que él
 * mismo no tiene, ni tocar el rol Dueño ni a los usuarios dueños.
 */
@Service
public class UserAdminService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final com.tiendaposs.tenant.PlanLimits planLimits;

    public UserAdminService(UserRepository users, RoleRepository roles, PasswordEncoder encoder, AuditService audit,
                            com.tiendaposs.tenant.PlanLimits planLimits) {
        this.planLimits = planLimits;
        this.users = users;
        this.roles = roles;
        this.encoder = encoder;
        this.audit = audit;
    }

    // ---------------- Usuarios ----------------

    @Transactional(readOnly = true)
    public List<UserDto> listUsers() {
        return users.findByTenantIdOrderByFullNameAsc(TenantContext.require()).stream().map(UserDto::of).toList();
    }

    @Transactional
    public UserDto createUser(CreateUserRequest req) {
        Long tenantId = TenantContext.require();
        String email = req.email().trim().toLowerCase();
        if (users.findByEmail(email).isPresent()) {
            throw ApiException.field("email", "ya está registrado");
        }
        Passwords.validate("password", req.password());
        Set<Role> assigned = resolveAssignableRoles(tenantId, req.roleIds());
        planLimits.checkUsers(tenantId);
        User u = new User();
        u.setTenantId(tenantId);
        u.setEmail(email);
        u.setFullName(req.fullName().trim());
        u.setPhone(blankToNull(req.phone()));
        u.setPasswordHash(encoder.encode(req.password()));
        u.setRoles(assigned);
        u = users.save(u);
        audit.sensitive("USUARIO_CREADO", "USER", u.getId(),
                "Creó el usuario " + u.getFullName() + " con roles: " + names(assigned));
        return UserDto.of(u);
    }

    @Transactional
    public UserDto updateUser(Long id, UpdateUserRequest req) {
        Long tenantId = TenantContext.require();
        User u = findUser(id);
        guardOwnerTarget(u);
        String email = req.email().trim().toLowerCase();
        users.findByEmail(email).filter(other -> !other.getId().equals(u.getId())).ifPresent(other -> {
            throw ApiException.field("email", "ya está registrado");
        });
        Set<Role> newRoles = resolveAssignableRoles(tenantId, req.roleIds());
        boolean wasOwner = u.isOwner();
        boolean staysOwner = newRoles.stream().anyMatch(Role::isOwnerRole);
        if (wasOwner && !staysOwner && u.isActive() && users.countActiveOwners(tenantId) <= 1) {
            throw ApiException.badRequest("La tienda debe tener al menos un dueño activo.");
        }
        String before = names(u.getRoles());
        u.setFullName(req.fullName().trim());
        u.setEmail(email);
        u.setPhone(blankToNull(req.phone()));
        u.setRoles(new HashSet<>(newRoles));
        String after = names(newRoles);
        if (!before.equals(after)) {
            audit.sensitive("ROLES_USUARIO", "USER", u.getId(),
                    "Cambió los roles de " + u.getFullName() + ": " + before + " → " + after);
        } else {
            audit.log("USUARIO_EDITADO", "USER", u.getId(), "Editó los datos de " + u.getFullName());
        }
        return UserDto.of(u);
    }

    @Transactional
    public UserDto setActive(Long id, boolean active) {
        User u = findUser(id);
        AuthUser me = CurrentUser.get();
        if (u.getId().equals(me.userId()) && !active) {
            throw ApiException.badRequest("No puedes desactivar tu propio usuario.");
        }
        guardOwnerTarget(u);
        if (!active && u.isOwner() && users.countActiveOwners(u.getTenantId()) <= 1) {
            throw ApiException.badRequest("La tienda debe tener al menos un dueño activo.");
        }
        if (u.isActive() == active) return UserDto.of(u);
        if (active) planLimits.checkUsers(u.getTenantId());
        u.setActive(active);
        if (!active) u.setTokenVersion(u.getTokenVersion() + 1);
        audit.sensitive(active ? "USUARIO_ACTIVADO" : "USUARIO_DESACTIVADO", "USER", u.getId(),
                (active ? "Reactivó a " : "Desactivó a ") + u.getFullName());
        return UserDto.of(u);
    }

    /** El dueño o administrador asigna una contraseña nueva a un empleado (p. ej. si la olvidó). */
    @Transactional
    public void setPassword(Long id, SetPasswordRequest req) {
        User u = findUser(id);
        guardOwnerTarget(u);
        Passwords.validate("password", req.password());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setTokenVersion(u.getTokenVersion() + 1);
        u.setFailedAttempts(0);
        u.setLockedUntil(null);
        audit.sensitive("CLAVE_ASIGNADA", "USER", u.getId(), "Asignó una contraseña nueva a " + u.getFullName());
    }

    @Transactional
    public UserDto unlock(Long id) {
        User u = findUser(id);
        u.setLockedUntil(null);
        u.setFailedAttempts(0);
        audit.sensitive("USUARIO_DESBLOQUEADO", "USER", u.getId(), "Desbloqueó a " + u.getFullName());
        return UserDto.of(u);
    }

    private User findUser(Long id) {
        return users.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
    }

    private void guardOwnerTarget(User target) {
        AuthUser me = CurrentUser.get();
        if (target.isOwner() && !me.owner()) {
            throw ApiException.forbidden("Solo un dueño puede modificar a otro dueño.");
        }
    }

    private Set<Role> resolveAssignableRoles(Long tenantId, Set<Long> roleIds) {
        AuthUser me = CurrentUser.get();
        Set<Role> result = new HashSet<>();
        for (Long rid : roleIds) {
            Role r = roles.findByIdAndTenantId(rid, tenantId)
                    .orElseThrow(() -> ApiException.field("roleIds", "uno de los roles no existe"));
            if (!me.owner()) {
                if (r.isOwnerRole()) {
                    throw ApiException.forbidden("Solo un dueño puede asignar el rol Dueño.");
                }
                if (!me.permissions().containsAll(r.getPermissions())) {
                    throw ApiException.forbidden("No puedes asignar el rol \"" + r.getName()
                            + "\" porque tiene permisos que tú no tienes.");
                }
            }
            result.add(r);
        }
        return result;
    }

    // ---------------- Roles ----------------

    @Transactional(readOnly = true)
    public List<RoleDto> listRoles() {
        return roles.findByTenantIdOrderByIdAsc(TenantContext.require()).stream().map(this::toDto).toList();
    }

    public List<PermissionDto> permissionCatalog() {
        return Arrays.stream(Permission.values()).map(p -> new PermissionDto(p.name(), p.group(), p.label())).toList();
    }

    @Transactional
    public RoleDto createRole(RoleRequest req) {
        Long tenantId = TenantContext.require();
        String name = req.name().trim();
        if (roles.nameTaken(tenantId, name, null)) throw ApiException.field("name", "ya existe un rol con ese nombre");
        Role r = new Role();
        r.setTenantId(tenantId);
        r.setName(name);
        r.setDescription(blankToNull(req.description()));
        r.setPermissions(validatePermissions(req.permissions()));
        r = roles.save(r);
        audit.sensitive("ROL_CREADO", "ROLE", r.getId(), "Creó el rol " + r.getName() + " con "
                + r.getPermissions().size() + " permisos");
        return toDto(r);
    }

    @Transactional
    public RoleDto updateRole(Long id, RoleRequest req) {
        Long tenantId = TenantContext.require();
        Role r = findRole(id);
        if (r.isOwnerRole()) throw ApiException.badRequest("El rol Dueño siempre tiene todos los permisos y no se edita.");
        String name = req.name().trim();
        if (roles.nameTaken(tenantId, name, r.getId())) throw ApiException.field("name", "ya existe un rol con ese nombre");
        Set<String> perms = validatePermissions(req.permissions());
        Set<String> added = new TreeSet<>(perms);
        added.removeAll(r.getPermissions());
        Set<String> removed = new TreeSet<>(r.getPermissions());
        removed.removeAll(perms);
        r.setName(name);
        r.setDescription(blankToNull(req.description()));
        r.getPermissions().clear();
        r.getPermissions().addAll(perms);
        if (!added.isEmpty() || !removed.isEmpty()) {
            audit.sensitive("PERMISOS_ROL", "ROLE", r.getId(), "Cambió permisos del rol " + r.getName()
                    + (added.isEmpty() ? "" : ". Agregó: " + String.join(", ", added))
                    + (removed.isEmpty() ? "" : ". Quitó: " + String.join(", ", removed)));
        } else {
            audit.log("ROL_EDITADO", "ROLE", r.getId(), "Editó el rol " + r.getName());
        }
        return toDto(r);
    }

    /** Devuelve un rol predefinido a sus permisos originales. */
    @Transactional
    public RoleDto resetRole(Long id) {
        Role r = findRole(id);
        if (r.getSystemCode() == null) throw ApiException.badRequest("Solo los roles predefinidos se pueden restablecer.");
        if (r.isOwnerRole()) return toDto(r);
        guardGrant(r.getSystemCode().permissions().stream().map(Permission::name).collect(Collectors.toSet()));
        r.setName(r.getSystemCode().displayName());
        r.setDescription(r.getSystemCode().description());
        r.getPermissions().clear();
        r.getSystemCode().permissions().forEach(p -> r.getPermissions().add(p.name()));
        audit.sensitive("PERMISOS_ROL", "ROLE", r.getId(), "Restableció los permisos originales del rol " + r.getName());
        return toDto(r);
    }

    @Transactional
    public void deleteRole(Long id) {
        Role r = findRole(id);
        if (r.getSystemCode() != null) throw ApiException.badRequest("Los roles predefinidos no se pueden eliminar.");
        long count = users.countByRole(r.getId());
        if (count > 0) {
            throw ApiException.badRequest("Este rol está asignado a " + count + (count == 1 ? " usuario" : " usuarios")
                    + ". Quítaselo antes de eliminarlo.");
        }
        roles.delete(r);
        audit.sensitive("ROL_ELIMINADO", "ROLE", r.getId(), "Eliminó el rol " + r.getName());
    }

    private Role findRole(Long id) {
        return roles.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> ApiException.notFound("Rol no encontrado"));
    }

    private Set<String> validatePermissions(Set<String> perms) {
        Set<String> clean = new HashSet<>();
        if (perms != null) {
            for (String p : perms) {
                if (!Permission.exists(p)) throw ApiException.field("permissions", "permiso desconocido: " + p);
                clean.add(p);
            }
        }
        guardGrant(clean);
        return clean;
    }

    private void guardGrant(Set<String> perms) {
        AuthUser me = CurrentUser.get();
        if (me.owner()) return;
        Set<String> missing = new TreeSet<>(perms);
        missing.removeAll(me.permissions());
        if (!missing.isEmpty()) {
            throw ApiException.forbidden("No puedes dar permisos que tú no tienes: " + String.join(", ", missing));
        }
    }

    private RoleDto toDto(Role r) {
        List<String> perms = r.isOwnerRole()
                ? SystemRole.DUENO.permissions().stream().map(Permission::name).sorted().toList()
                : r.getPermissions().stream().sorted().toList();
        return new RoleDto(r.getId(), r.getName(), r.getDescription(),
                r.getSystemCode() == null ? null : r.getSystemCode().name(),
                !r.isOwnerRole(), r.getSystemCode() == null, perms, users.countByRole(r.getId()));
    }

    private static String names(Collection<Role> rs) {
        return rs.stream().map(Role::getName).sorted().collect(Collectors.joining(", "));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
