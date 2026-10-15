package com.tiendaposs.users;

import com.tiendaposs.users.UserDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class UserAdminController {

    private final UserAdminService service;

    public UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping("/users")
    @PreAuthorize("hasAnyAuthority('USUARIOS_GESTIONAR','AUDITORIA_VER','CAJA_SUPERVISAR','REPORTES_VER')")
    public List<UserDto> list() {
        return service.listUsers();
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('USUARIOS_GESTIONAR')")
    public UserDto create(@Valid @RequestBody CreateUserRequest req) {
        return service.createUser(req);
    }

    @PutMapping("/users/{id}")
    @PreAuthorize("hasAuthority('USUARIOS_GESTIONAR')")
    public UserDto update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest req) {
        return service.updateUser(id, req);
    }

    @PostMapping("/users/{id}/deactivate")
    @PreAuthorize("hasAuthority('USUARIOS_GESTIONAR')")
    public UserDto deactivate(@PathVariable Long id) {
        return service.setActive(id, false);
    }

    @PostMapping("/users/{id}/activate")
    @PreAuthorize("hasAuthority('USUARIOS_GESTIONAR')")
    public UserDto activate(@PathVariable Long id) {
        return service.setActive(id, true);
    }

    @PostMapping("/users/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('USUARIOS_GESTIONAR')")
    public void setPassword(@PathVariable Long id, @Valid @RequestBody SetPasswordRequest req) {
        service.setPassword(id, req);
    }

    @PostMapping("/users/{id}/unlock")
    @PreAuthorize("hasAuthority('USUARIOS_GESTIONAR')")
    public UserDto unlock(@PathVariable Long id) {
        return service.unlock(id);
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAnyAuthority('USUARIOS_GESTIONAR','ROLES_GESTIONAR','AUDITORIA_VER')")
    public List<RoleDto> roles() {
        return service.listRoles();
    }

    @GetMapping("/permissions")
    public List<PermissionDto> permissions() {
        return service.permissionCatalog();
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ROLES_GESTIONAR')")
    public RoleDto createRole(@Valid @RequestBody RoleRequest req) {
        return service.createRole(req);
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('ROLES_GESTIONAR')")
    public RoleDto updateRole(@PathVariable Long id, @Valid @RequestBody RoleRequest req) {
        return service.updateRole(id, req);
    }

    @PostMapping("/roles/{id}/reset")
    @PreAuthorize("hasAuthority('ROLES_GESTIONAR')")
    public RoleDto resetRole(@PathVariable Long id) {
        return service.resetRole(id);
    }

    @DeleteMapping("/roles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('ROLES_GESTIONAR')")
    public void deleteRole(@PathVariable Long id) {
        service.deleteRole(id);
    }
}
