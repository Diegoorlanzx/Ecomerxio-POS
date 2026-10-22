package com.tiendaposs.payroll;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiendaposs.accounting.AccountingService;
import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.einvoice.EInvoiceRepos.DocumentRepository;
import com.tiendaposs.einvoice.EInvoiceService;
import com.tiendaposs.einvoice.ElectronicDocument;
import com.tiendaposs.fiscal.FiscalCodes;
import com.tiendaposs.payroll.PayrollRepos.*;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.tenant.TenantContext;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/**
 * Fase 16F: nómina. Empleados → parámetros del año → periodo (mes) con novedades → liquidar → aprobar (comprobante
 * contable) → transmitir la nómina electrónica (un documento por empleado) → si hace falta, eliminarla ante la DIAN
 * con la nota de ajuste. No hace la planilla PILA: muestra los aportes para pagarlos en el operador.
 */
@Service
public class PayrollService {

    private static final ZoneId ZONE = ZoneId.of("America/Bogota");
    static final Set<String> KINDS = Set.of("HORA", "INCAPACIDAD", "VACACIONES", "LICENCIA", "BONIFICACION", "COMISION", "AUXILIO",
            "OTRO_DEVENGO", "PRESTAMO", "LIBRANZA", "RETENCION", "OTRA_DEDUCCION");

    private final EmployeeRepository employees;
    private final ParametersRepository parameters;
    private final PeriodRepository periods;
    private final NoveltyRepository novelties;
    private final PayslipRepository payslips;
    private final EInvoiceService einvoice;
    private final DocumentRepository docs;
    private final AccountingService accounting;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();

    public PayrollService(EmployeeRepository employees, ParametersRepository parameters, PeriodRepository periods,
                          NoveltyRepository novelties, PayslipRepository payslips, EInvoiceService einvoice, DocumentRepository docs,
                          AccountingService accounting, AuditService audit) {
        this.employees = employees;
        this.parameters = parameters;
        this.periods = periods;
        this.novelties = novelties;
        this.payslips = payslips;
        this.einvoice = einvoice;
        this.docs = docs;
        this.accounting = accounting;
        this.audit = audit;
    }

    // =================================================================== Parámetros del año

    public record ParamsRequest(@Min(2024) @Max(2100) int year,
                                @NotNull @DecimalMin(value = "1", message = "escribe el salario mínimo") BigDecimal minimumWage,
                                @NotNull @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal transportAid,
                                @NotNull @DecimalMin(value = "100", message = "revisa las horas del mes") @DecimalMax(value = "240", message = "revisa las horas del mes") BigDecimal monthlyHours,
                                @NotNull @DecimalMin(value = "0") @DecimalMax(value = "200") BigDecimal sundaySurchargePct,
                                boolean exonerated) {
    }

    @Transactional(readOnly = true)
    public List<PayrollParameters> listParameters() {
        CurrentUser.require(Permission.NOMINA_VER);
        return parameters.findByTenantIdOrderByYearDesc(TenantContext.require());
    }

    @Transactional
    public PayrollParameters saveParameters(ParamsRequest r) {
        CurrentUser.require(Permission.NOMINA_GESTIONAR);
        Long t = TenantContext.require();
        PayrollParameters p = parameters.findByTenantIdAndYear(t, r.year()).orElseGet(PayrollParameters::new);
        p.tenantId = t;
        p.year = r.year();
        p.minimumWage = r.minimumWage();
        p.transportAid = r.transportAid();
        p.monthlyHours = r.monthlyHours();
        p.sundaySurchargePct = r.sundaySurchargePct();
        p.exonerated = r.exonerated();
        p.confirmedBy = CurrentUser.get().fullName();
        p.updatedAt = Instant.now();
        audit.sensitive("NOMINA_PARAMETROS", "TENANT", t, "Confirmó los valores de nómina de " + r.year() + ": mínimo "
                + r.minimumWage() + ", auxilio " + r.transportAid());
        return parameters.save(p);
    }

    // =================================================================== Empleados

    public record EmployeeRequest(Long userId,
                                  @NotBlank(message = "elige el tipo de documento") String docType,
                                  @NotBlank(message = "escribe el documento") @Size(max = 30) String docNumber,
                                  @NotBlank(message = "escribe el primer nombre") @Size(max = 60) String firstName,
                                  @Size(max = 60) String otherNames,
                                  @NotBlank(message = "escribe el primer apellido") @Size(max = 60) String firstSurname,
                                  @Size(max = 60) String secondSurname,
                                  @Email(message = "no es un correo válido") String email, @Size(max = 30) String phone,
                                  @Size(max = 200) String address,
                                  @Pattern(regexp = "^$|[0-9]{5}", message = "son 5 dígitos (código DANE)") String municipalityCode,
                                  @NotBlank @Pattern(regexp = "[1-5]", message = "tipo de contrato no válido") String contractType,
                                  boolean integralSalary, boolean highRisk,
                                  @NotNull @DecimalMin(value = "1", message = "escribe el salario") BigDecimal salary,
                                  @Min(1) @Max(5) int arlClass,
                                  @NotNull(message = "escribe la fecha de ingreso") LocalDate startDate, LocalDate endDate,
                                  @Size(max = 80) String eps, @Size(max = 80) String pensionFund, @Size(max = 80) String severanceFund,
                                  @Size(max = 80) String compensationFund,
                                  @Pattern(regexp = "10|42|47", message = "elige efectivo, consignación o transferencia") String paymentMethod,
                                  @Size(max = 80) String bankName, @Pattern(regexp = "^$|[1-3]", message = "tipo de cuenta no válido") String accountType,
                                  @Size(max = 40) String accountNumber, Boolean active) {
    }

    @Transactional(readOnly = true)
    public List<Employee> listEmployees() {
        CurrentUser.require(Permission.NOMINA_VER);
        return employees.findByTenantIdOrderByFirstSurnameAscFirstNameAsc(TenantContext.require());
    }

    @Transactional
    public Employee saveEmployee(Long id, EmployeeRequest r) {
        CurrentUser.require(Permission.NOMINA_GESTIONAR);
        Long t = TenantContext.require();
        Employee e = id == null ? new Employee() : employees.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Empleado no encontrado"));
        if (!FiscalCodes.ID_TYPES.containsKey(r.docType())) throw ApiException.field("docType", "tipo de documento no válido");
        String doc = r.docNumber().replaceAll("[^0-9A-Za-z]", "");
        employees.findByTenantIdAndDocNumber(t, doc).filter(o -> !o.id.equals(e.id)).ifPresent(o -> {
            throw ApiException.field("docNumber", "ya hay un empleado con ese documento: " + o.fullName());
        });
        if (r.endDate() != null && r.endDate().isBefore(r.startDate())) throw ApiException.field("endDate", "no puede ser antes del ingreso");
        if (Set.of("42", "47").contains(r.paymentMethod()) && (blank(r.bankName()) == null || blank(r.accountNumber()) == null || blank(r.accountType()) == null)) {
            throw ApiException.field("accountNumber", "para pagar por banco escribe banco, tipo y número de cuenta");
        }
        parameters.findByTenantIdAndYear(t, r.startDate().getYear() > LocalDate.now(ZONE).getYear() ? r.startDate().getYear() : LocalDate.now(ZONE).getYear())
                .ifPresent(p -> {
                    if (!Set.of("4", "5").contains(r.contractType()) && r.salary().compareTo(p.minimumWage) < 0) {
                        throw ApiException.field("salary", "no puede ser menor que el salario mínimo (" + p.minimumWage.toPlainString() + ")");
                    }
                    if (r.integralSalary() && r.salary().compareTo(p.minimumWage.multiply(BigDecimal.valueOf(13))) < 0) {
                        throw ApiException.field("salary", "el salario integral es de al menos 13 salarios mínimos");
                    }
                });
        e.tenantId = t;
        e.userId = r.userId();
        e.docType = r.docType();
        e.docNumber = doc;
        e.firstName = r.firstName().trim();
        e.otherNames = blank(r.otherNames());
        e.firstSurname = r.firstSurname().trim();
        e.secondSurname = blank(r.secondSurname());
        e.email = blank(r.email());
        e.phone = blank(r.phone());
        e.address = blank(r.address());
        e.municipalityCode = blank(r.municipalityCode());
        e.contractType = r.contractType();
        e.integralSalary = r.integralSalary();
        e.highRisk = r.highRisk();
        e.salary = r.salary();
        e.arlClass = r.arlClass() == 0 ? 1 : r.arlClass();
        e.startDate = r.startDate();
        e.endDate = r.endDate();
        e.eps = blank(r.eps());
        e.pensionFund = blank(r.pensionFund());
        e.severanceFund = blank(r.severanceFund());
        e.compensationFund = blank(r.compensationFund());
        e.paymentMethod = r.paymentMethod() == null ? "10" : r.paymentMethod();
        e.bankName = blank(r.bankName());
        e.accountType = blank(r.accountType());
        e.accountNumber = blank(r.accountNumber());
        if (r.active() != null) e.active = r.active();
        Employee saved = employees.save(e);
        audit.log(id == null ? "EMPLEADO_CREADO" : "EMPLEADO_EDITADO", "EMPLOYEE", saved.id, (id == null ? "Creó" : "Editó") + " el empleado " + saved.fullName());
        return saved;
    }

    // =================================================================== Periodos y novedades

    public record PeriodSummary(Long id, int year, int month, String status, LocalDate paymentDate, int employees, BigDecimal totalNet,
                                BigDecimal totalCost, String approvedBy) {
    }

    public record PayslipDto(Long id, Long employeeId, String employeeName, String docNumber, int daysWorked, BigDecimal contributionBase,
                             BigDecimal totalEarned, BigDecimal totalDeductions, BigDecimal netPay, BigDecimal employerCost, BigDecimal provisions,
                             Object detail, boolean deletedBeforeDian, EInvoiceService.DocumentDto document) {
    }

    public record PeriodDetail(PeriodSummary period, List<PayslipDto> payslips, List<PayrollNovelty> novelties, PayrollParameters parameters) {
    }

    public record PeriodRequest(@Min(2024) @Max(2100) int year, @Min(1) @Max(12) int month) {
    }

    @Transactional(readOnly = true)
    public List<PeriodSummary> listPeriods() {
        CurrentUser.require(Permission.NOMINA_VER);
        return periods.findByTenantIdOrderByYearDescMonthDesc(TenantContext.require()).stream().map(this::summary).toList();
    }

    @Transactional
    public PeriodDetail createPeriod(PeriodRequest r) {
        CurrentUser.require(Permission.NOMINA_GESTIONAR);
        Long t = TenantContext.require();
        params(t, r.year());
        if (periods.findByTenantIdAndYearAndMonth(t, r.year(), r.month()).isPresent()) {
            throw ApiException.badRequest("Ya existe la nómina de " + r.month() + "/" + r.year() + ".");
        }
        PayrollPeriod p = new PayrollPeriod();
        p.tenantId = t;
        p.year = r.year();
        p.month = r.month();
        p.paymentDate = YearMonth.of(r.year(), r.month()).atEndOfMonth();
        p = periods.save(p);
        liquidate(p);
        audit.log("NOMINA_PERIODO", "PAYROLL", p.id, "Abrió la nómina de " + r.month() + "/" + r.year());
        return detail(p.id);
    }

    @Transactional(readOnly = true)
    public PeriodDetail detail(Long periodId) {
        CurrentUser.require(Permission.NOMINA_VER);
        PayrollPeriod p = period(periodId);
        Map<Long, Employee> emps = new HashMap<>();
        employees.findByTenantIdOrderByFirstSurnameAscFirstNameAsc(p.tenantId).forEach(e -> emps.put(e.id, e));
        List<PayslipDto> slips = payslips.findByPeriodIdOrderByIdAsc(p.id).stream().map(s -> dto(s, emps.get(s.employeeId))).toList();
        return new PeriodDetail(summary(p), slips, novelties.findByPeriodIdOrderByIdAsc(p.id),
                parameters.findByTenantIdAndYear(p.tenantId, p.year).orElse(null));
    }

    public record NoveltyRequest(@NotNull Long employeeId, @NotBlank String kind, @Size(max = 4) String code,
                                 @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal quantity,
                                 @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal percentage,
                                 @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal amount,
                                 @Size(max = 200) String description) {
    }

    @Transactional
    public PeriodDetail addNovelty(Long periodId, NoveltyRequest r) {
        CurrentUser.require(Permission.NOMINA_GESTIONAR);
        PayrollPeriod p = draft(periodId);
        if (!KINDS.contains(r.kind())) throw ApiException.field("kind", "tipo de novedad no válido");
        employees.findByIdAndTenantId(r.employeeId(), p.tenantId).orElseThrow(() -> ApiException.notFound("Empleado no encontrado"));
        boolean needsQty = Set.of("HORA", "INCAPACIDAD", "VACACIONES", "LICENCIA").contains(r.kind());
        if (needsQty && (r.quantity() == null || r.quantity().signum() <= 0)) throw ApiException.field("quantity", "escribe las horas o los días");
        if (!needsQty && (r.amount() == null || r.amount().signum() <= 0)) throw ApiException.field("amount", "escribe el valor");
        if ("HORA".equals(r.kind()) && (r.code() == null || !r.code().matches("[1-7]"))) throw ApiException.field("code", "elige el tipo de hora extra o recargo");
        PayrollNovelty n = new PayrollNovelty();
        n.tenantId = p.tenantId;
        n.periodId = p.id;
        n.employeeId = r.employeeId();
        n.kind = r.kind();
        n.code = r.code();
        n.quantity = r.quantity();
        n.percentage = r.percentage();
        n.amount = r.amount();
        n.description = r.description();
        novelties.save(n);
        liquidate(p);
        return detail(p.id);
    }

    @Transactional
    public PeriodDetail deleteNovelty(Long periodId, Long noveltyId) {
        CurrentUser.require(Permission.NOMINA_GESTIONAR);
        PayrollPeriod p = draft(periodId);
        novelties.findById(noveltyId).filter(n -> n.periodId.equals(p.id)).ifPresent(novelties::delete);
        novelties.flush();
        liquidate(p);
        return detail(p.id);
    }

    @Transactional
    public PeriodDetail recalculate(Long periodId) {
        CurrentUser.require(Permission.NOMINA_GESTIONAR);
        PayrollPeriod p = draft(periodId);
        liquidate(p);
        return detail(p.id);
    }

    /** Liquida a todos los empleados con contrato en el mes (los que ya no aplican salen del periodo). */
    private void liquidate(PayrollPeriod p) {
        PayrollParameters par = params(p.tenantId, p.year);
        YearMonth ym = YearMonth.of(p.year, p.month);
        var cp = new PayrollCalculator.Params(par.minimumWage, par.transportAid, par.monthlyHours, par.sundaySurchargePct, par.exonerated);
        Set<Long> included = new HashSet<>();
        for (Employee e : employees.findByTenantIdOrderByFirstSurnameAscFirstNameAsc(p.tenantId)) {
            boolean inMonth = !e.startDate.isAfter(ym.atEndOfMonth()) && (e.endDate == null || !e.endDate.isBefore(ym.atDay(1)));
            if (!inMonth || (!e.active && e.endDate == null)) continue;
            List<PayrollCalculator.Novelty> ns = novelties.findByPeriodIdAndEmployeeIdOrderByIdAsc(p.id, e.id).stream()
                    .map(n -> new PayrollCalculator.Novelty(n.kind, n.code, n.quantity, n.percentage, n.amount, n.description)).toList();
            PayrollCalculator.Result r;
            try {
                r = PayrollCalculator.calculate(new PayrollCalculator.Worker(e.salary, e.integralSalary, e.arlClass, e.startDate, e.endDate), cp, ym, ns);
            } catch (IllegalArgumentException ex) {
                throw ApiException.badRequest(e.fullName() + ": " + ex.getMessage());
            }
            Payslip s = payslips.findByPeriodIdAndEmployeeId(p.id, e.id).orElseGet(Payslip::new);
            s.tenantId = p.tenantId;
            s.periodId = p.id;
            s.employeeId = e.id;
            s.daysWorked = r.daysWorked();
            s.contributionBase = r.contributionBase();
            s.totalEarned = r.totalEarned();
            s.totalDeductions = r.totalDeductions();
            s.netPay = r.netPay();
            s.employerCost = r.employerTotal();
            s.provisions = r.provisionsTotal();
            try {
                s.detail = json.writeValueAsString(Map.of("earnings", r.earnings(), "deductions", r.deductions(), "employer", r.employer(),
                        "provisions", r.provisions()));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            payslips.save(s);
            included.add(e.id);
        }
        payslips.findByPeriodIdOrderByIdAsc(p.id).stream().filter(s -> !included.contains(s.employeeId)).forEach(payslips::delete);
    }

    public record ApproveRequest(LocalDate paymentDate) {
    }

    @Transactional
    public PeriodDetail approve(Long periodId, ApproveRequest r) {
        CurrentUser.require(Permission.NOMINA_GESTIONAR);
        PayrollPeriod p = draft(periodId);
        liquidate(p);
        List<Payslip> slips = payslips.findByPeriodIdOrderByIdAsc(p.id);
        if (slips.isEmpty()) throw ApiException.badRequest("No hay empleados con contrato en este mes.");
        p.status = "APROBADA";
        if (r != null && r.paymentDate() != null) p.paymentDate = r.paymentDate();
        p.approvedBy = CurrentUser.get().fullName();
        p.approvedAt = Instant.now();
        periods.save(p);
        accounting.onPayrollApproved(p.tenantId, p.id, p.paymentDate, p.month + "/" + p.year,
                slips.stream().map(s -> s.totalEarned).reduce(BigDecimal.ZERO, BigDecimal::add),
                slips.stream().map(s -> s.totalDeductions).reduce(BigDecimal.ZERO, BigDecimal::add),
                slips.stream().map(s -> s.netPay).reduce(BigDecimal.ZERO, BigDecimal::add),
                slips.stream().map(s -> s.employerCost).reduce(BigDecimal.ZERO, BigDecimal::add),
                slips.stream().map(s -> s.provisions).reduce(BigDecimal.ZERO, BigDecimal::add));
        audit.sensitive("NOMINA_APROBADA", "PAYROLL", p.id, "Aprobó la nómina de " + p.month + "/" + p.year + " (" + slips.size() + " empleados)");
        return detail(p.id);
    }

    /** Un documento de nómina electrónica por empleado (los que ya existen no se repiten). */
    public PeriodDetail transmit(Long periodId) {
        CurrentUser.require(Permission.NOMINA_GESTIONAR);
        PayrollPeriod p = period(periodId);
        if (!"APROBADA".equals(p.status)) throw ApiException.badRequest("Aprueba primero la nómina del mes.");
        for (Payslip s : payslips.findByPeriodIdOrderByIdAsc(p.id)) {
            if (s.deletedBeforeDian || docs.findPayrollDocument(s.id).isPresent()) continue;
            einvoice.issuePayroll(s.id);
        }
        einvoice.processDue();
        return detail(p.id);
    }

    /** Nota de ajuste (eliminación) de la nómina electrónica ya aceptada de un empleado. */
    public PeriodDetail deleteBeforeDian(Long payslipId, String reason) {
        CurrentUser.require(Permission.NOMINA_GESTIONAR);
        Long t = TenantContext.require();
        Payslip s = payslips.findByIdAndTenantId(payslipId, t).orElseThrow(() -> ApiException.notFound("Liquidación no encontrada"));
        ElectronicDocument d = docs.findPayrollDocument(s.id).orElseThrow(() -> ApiException.badRequest("Esa liquidación no tiene nómina electrónica."));
        einvoice.issuePayrollDeletion(d.id, reason == null || reason.isBlank() ? "Eliminación de la nómina electrónica" : reason.trim());
        einvoice.processDue();
        return detail(s.periodId);
    }

    // =================================================================== utilidades

    private PayrollParameters params(Long t, int year) {
        return parameters.findByTenantIdAndYear(t, year).orElseThrow(() -> ApiException.badRequest(
                "Confirma primero los valores de nómina de " + year + " (salario mínimo, auxilio de transporte, horas del mes)."));
    }

    private PayrollPeriod period(Long id) {
        return periods.findByIdAndTenantId(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Nómina no encontrada"));
    }

    private PayrollPeriod draft(Long id) {
        PayrollPeriod p = period(id);
        if (!"BORRADOR".equals(p.status)) throw ApiException.badRequest("La nómina ya está aprobada: no se puede cambiar.");
        return p;
    }

    private PeriodSummary summary(PayrollPeriod p) {
        List<Payslip> slips = payslips.findByPeriodIdOrderByIdAsc(p.id);
        return new PeriodSummary(p.id, p.year, p.month, p.status, p.paymentDate, slips.size(),
                slips.stream().map(s -> s.netPay).reduce(BigDecimal.ZERO, BigDecimal::add),
                slips.stream().map(s -> s.totalEarned.add(s.employerCost).add(s.provisions)).reduce(BigDecimal.ZERO, BigDecimal::add),
                p.approvedBy);
    }

    private PayslipDto dto(Payslip s, Employee e) {
        Object detail;
        try {
            detail = json.readValue(s.detail, Map.class);
        } catch (Exception ex) {
            detail = Map.of();
        }
        return new PayslipDto(s.id, s.employeeId, e == null ? "" : e.fullName(), e == null ? null : e.docNumber, s.daysWorked,
                s.contributionBase, s.totalEarned, s.totalDeductions, s.netPay, s.employerCost, s.provisions, detail, s.deletedBeforeDian,
                docs.findPayrollDocument(s.id).map(einvoice::dto).orElse(null));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
