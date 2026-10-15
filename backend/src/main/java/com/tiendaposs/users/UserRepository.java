package com.tiendaposs.users;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    @Query("select u from User u where lower(u.email) = lower(?1)")
    Optional<User> findByEmail(String email);

    List<User> findByTenantIdOrderByFullNameAsc(Long tenantId);

    Optional<User> findByIdAndTenantId(Long id, Long tenantId);

    @Query("select count(u) from User u join u.roles r where u.tenantId = ?1 and u.active = true and r.systemCode = com.tiendaposs.users.SystemRole.DUENO")
    long countActiveOwners(Long tenantId);

    @Query("select count(u) from User u join u.roles r where r.id = ?1")
    long countByRole(Long roleId);
}
