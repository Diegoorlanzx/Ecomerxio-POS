package com.tiendaposs.payroll;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public final class PayrollRepos {
    private PayrollRepos() {
    }

    public interface EmployeeRepository extends JpaRepository<Employee, Long> {
        List<Employee> findByTenantIdOrderByFirstSurnameAscFirstNameAsc(Long tenantId);

        Optional<Employee> findByIdAndTenantId(Long id, Long tenantId);

        Optional<Employee> findByTenantIdAndDocNumber(Long tenantId, String docNumber);
    }

    public interface ParametersRepository extends JpaRepository<PayrollParameters, Long> {
        Optional<PayrollParameters> findByTenantIdAndYear(Long tenantId, int year);

        List<PayrollParameters> findByTenantIdOrderByYearDesc(Long tenantId);
    }

    public interface PeriodRepository extends JpaRepository<PayrollPeriod, Long> {
        List<PayrollPeriod> findByTenantIdOrderByYearDescMonthDesc(Long tenantId);

        Optional<PayrollPeriod> findByIdAndTenantId(Long id, Long tenantId);

        Optional<PayrollPeriod> findByTenantIdAndYearAndMonth(Long tenantId, int year, int month);
    }

    public interface NoveltyRepository extends JpaRepository<PayrollNovelty, Long> {
        List<PayrollNovelty> findByPeriodIdOrderByIdAsc(Long periodId);

        List<PayrollNovelty> findByPeriodIdAndEmployeeIdOrderByIdAsc(Long periodId, Long employeeId);
    }

    public interface PayslipRepository extends JpaRepository<Payslip, Long> {
        List<Payslip> findByPeriodIdOrderByIdAsc(Long periodId);

        Optional<Payslip> findByPeriodIdAndEmployeeId(Long periodId, Long employeeId);

        Optional<Payslip> findByIdAndTenantId(Long id, Long tenantId);
    }
}
