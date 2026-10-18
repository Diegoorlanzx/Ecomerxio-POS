package com.tiendaposs.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface LoginHandoffRepository extends JpaRepository<LoginHandoff, Long> {

    Optional<LoginHandoff> findByCodeHash(String codeHash);

    /** Marca el código como usado solo si sigue vigente: devuelve 1 una única vez aunque lleguen dos canjes juntos. */
    @Modifying
    @Query("update LoginHandoff h set h.usedAt = ?2 where h.codeHash = ?1 and h.usedAt is null and h.expiresAt > ?2")
    int consume(String codeHash, Instant now);

    @Modifying
    @Query("delete from LoginHandoff h where h.expiresAt < ?1")
    int deleteExpiredBefore(Instant cutoff);
}
