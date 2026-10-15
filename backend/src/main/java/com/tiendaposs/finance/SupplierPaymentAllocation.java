package com.tiendaposs.finance;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "supplier_payment_allocations")
public class SupplierPaymentAllocation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long paymentId;
    public Long receiptId;
    public BigDecimal amount;
}
