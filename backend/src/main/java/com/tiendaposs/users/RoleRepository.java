package com.tiendaposs.users;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    List<Role> findByTenantIdOrderByIdAsc(Long tenantId);

    Optional<Role> findByIdAndTenantId(Long id, Long tenantId);

    Optional<Role> findByTenantIdAndSystemCode(Long tenantId, SystemRole code);

    @Query("select count(r) > 0 from Role r where r.tenantId = ?1 and lower(r.name) = lower(?2) and (?3 is null or r.id <> ?3)")
    boolean nameTaken(Long tenantId, String name, Long excludeId);
}
