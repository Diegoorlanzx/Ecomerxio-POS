package com.tiendaposs.accounting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public final class AccountingRepos {
    private AccountingRepos() {
    }

    public interface SettingsRepository extends JpaRepository<AccountingSettings, Long> {
    }

    public interface AccountRepository extends JpaRepository<Account, Long> {
        List<Account> findByTenantIdOrderByCodeAsc(Long tenantId);

        Optional<Account> findByTenantIdAndCode(Long tenantId, String code);

        Optional<Account> findByIdAndTenantId(Long id, Long tenantId);
    }

    public interface MapRepository extends JpaRepository<MapEntry, MapEntry.Key> {
        List<MapEntry> findByTenantId(Long tenantId);
    }

    public interface EntryRepository extends JpaRepository<JournalEntry, Long> {
        Optional<JournalEntry> findByTenantIdAndSourceTypeAndSourceIdAndEntryType(Long tenantId, String sourceType, Long sourceId, String entryType);

        @Query("select e from JournalEntry e where e.tenantId = ?1 and e.entryDate between ?2 and ?3 order by e.entryDate desc, e.number desc")
        List<JournalEntry> between(Long tenantId, LocalDate from, LocalDate to, org.springframework.data.domain.Pageable page);

        long countByTenantId(Long tenantId);

        boolean existsByTenantIdAndSourceTypeAndSourceId(Long tenantId, String sourceType, Long sourceId);

        Optional<JournalEntry> findFirstByTenantIdAndSourceTypeAndSourceId(Long tenantId, String sourceType, Long sourceId);

        @Query("select count(e) from JournalEntry e where e.tenantId = ?1 and e.entryDate between ?2 and ?3")
        long countBetween(Long tenantId, LocalDate from, LocalDate to);
    }

    public interface LineRepository extends JpaRepository<JournalLine, Long> {
        List<JournalLine> findByEntryIdOrderByIdAsc(Long entryId);

        List<JournalLine> findByEntryIdIn(java.util.Collection<Long> entryIds);

        @Query("select count(l) > 0 from JournalLine l where l.tenantId = ?1 and l.accountCode = ?2")
        boolean hasMovements(Long tenantId, String code);
    }

    public interface PendingRepository extends JpaRepository<PendingEntry, Long> {
        List<PendingEntry> findByTenantIdAndResolvedAtIsNullOrderByEntryDateAscIdAsc(Long tenantId);

        Optional<PendingEntry> findFirstByTenantIdAndSourceTypeAndSourceIdAndResolvedAtIsNull(Long tenantId, String sourceType, Long sourceId);

        long countByTenantIdAndResolvedAtIsNull(Long tenantId);

        @Query("select count(p) from PendingEntry p where p.tenantId = ?1 and p.resolvedAt is null and p.entryDate between ?2 and ?3")
        long countOpenBetween(Long tenantId, LocalDate from, LocalDate to);
    }

    public interface WithholdingRepository extends JpaRepository<ReceiptWithholding, Long> {
        List<ReceiptWithholding> findByReceiptIdOrderByIdAsc(Long receiptId);

        void deleteByReceiptId(Long receiptId);
    }

    public interface TaxSettingsRepository extends JpaRepository<AccountingTaxSettings, Long> {
    }

    public interface PeriodRepository extends JpaRepository<AccountingPeriod, AccountingPeriod.Key> {
        List<AccountingPeriod> findByTenantIdOrderByPeriodDesc(Long tenantId);
    }
}
