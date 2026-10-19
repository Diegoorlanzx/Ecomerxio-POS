package com.tiendaposs.accounting;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;

/** Una fila del mapa contable: qué cuenta usa una cosa (PAY:EFECTIVO, SALES, TAX:IVA...). */
@Entity
@Table(name = "accounting_map")
@IdClass(MapEntry.Key.class)
public class MapEntry {
    @Id
    public Long tenantId;
    @Id
    public String mapKey;
    public String accountCode;

    public static class Key implements Serializable {
        public Long tenantId;
        public String mapKey;

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(tenantId, k.tenantId) && Objects.equals(mapKey, k.mapKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, mapKey);
        }
    }
}
