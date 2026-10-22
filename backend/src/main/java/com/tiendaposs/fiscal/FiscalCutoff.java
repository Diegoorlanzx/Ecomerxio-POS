package com.tiendaposs.fiscal;

import com.tiendaposs.fiscal.FiscalRepos.FiscalProfileRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Fase 16B: fecha de corte de la facturación electrónica. Las ventas anteriores nunca se facturan y, si el dueño lo deja
 * así (por defecto), no se muestran en Ventas, Analítica, Dashboard ni reportes. No se borran: siguen en las copias de
 * seguridad, en sus devoluciones y en la cartera de fiados, y el dueño puede verlas con su permiso.
 */
@Component
public class FiscalCutoff {

    private final FiscalProfileRepository profiles;

    public FiscalCutoff(FiscalProfileRepository profiles) {
        this.profiles = profiles;
    }

    /** Fecha de corte (null si la tienda nunca activó la facturación). */
    public Instant startAt(Long tenantId) {
        return profiles.findById(tenantId).map(p -> p.fiscalStartAt).orElse(null);
    }

    /** Desde cuándo se muestran las ventas en informes (null = todas). */
    public Instant hiddenBefore(Long tenantId) {
        return profiles.findById(tenantId).filter(p -> p.hidePreCutoff && p.fiscalStartAt != null).map(p -> p.fiscalStartAt).orElse(null);
    }

    /** El mayor entre el inicio pedido y la fecha de corte (si se ocultan las ventas anteriores). */
    public Instant clamp(Long tenantId, Instant from) {
        Instant cut = hiddenBefore(tenantId);
        return cut != null && cut.isAfter(from) ? cut : from;
    }
}
