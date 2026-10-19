package com.tiendaposs.accounting;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "journal_lines")
public class JournalLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long entryId;
    public String accountCode;
    public String thirdPartyId;
    public String thirdPartyName;
    public BigDecimal debit = BigDecimal.ZERO;
    public BigDecimal credit = BigDecimal.ZERO;
    public String description;
}
