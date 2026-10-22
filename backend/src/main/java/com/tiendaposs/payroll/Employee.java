package com.tiendaposs.payroll;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Fase 16F: un empleado de la nómina (puede o no ser usuario del POS). */
@Entity
@Table(name = "employees")
public class Employee {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long userId;
    public String docType;
    public String docNumber;
    public String firstName;
    public String otherNames;
    public String firstSurname;
    public String secondSurname;
    public String email;
    public String phone;
    public String address;
    public String municipalityCode;
    /** 1 fijo, 2 indefinido, 3 obra o labor, 4 aprendizaje, 5 práctica. */
    public String contractType;
    public String workerType = "01";
    public String workerSubtype = "00";
    public boolean integralSalary;
    public boolean highRisk;
    public BigDecimal salary;
    public int arlClass = 1;
    public LocalDate startDate;
    public LocalDate endDate;
    public String eps;
    public String pensionFund;
    public String severanceFund;
    public String compensationFund;
    public String paymentMethod = "10";
    public String bankName;
    public String accountType;
    public String accountNumber;
    public boolean active = true;
    public Instant createdAt = Instant.now();

    public String fullName() {
        return String.join(" ", java.util.stream.Stream.of(firstName, otherNames, firstSurname, secondSurname)
                .filter(s -> s != null && !s.isBlank()).toList());
    }
}
