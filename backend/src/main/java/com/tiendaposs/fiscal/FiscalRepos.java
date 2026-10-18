package com.tiendaposs.fiscal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public final class FiscalRepos {
    private FiscalRepos() {
    }

    public interface FiscalProfileRepository extends JpaRepository<FiscalProfile, Long> {
    }

    public interface ResolutionRepository extends JpaRepository<NumberingResolution, Long> {
        List<NumberingResolution> findByTenantIdOrderByActiveDescValidToDesc(Long tenantId);

        List<NumberingResolution> findByTenantIdAndActiveTrue(Long tenantId);

        Optional<NumberingResolution> findByIdAndTenantId(Long id, Long tenantId);
    }
}
