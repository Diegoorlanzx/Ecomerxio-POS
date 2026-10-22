package com.tiendaposs.payroll;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Fase 16F: nómina y nómina electrónica. */
@RestController
@RequestMapping("/api/payroll")
@PreAuthorize("hasAnyAuthority('NOMINA_VER','NOMINA_GESTIONAR')")
public class PayrollController {

    private final PayrollService payroll;

    public PayrollController(PayrollService payroll) {
        this.payroll = payroll;
    }

    @GetMapping("/parameters")
    public List<PayrollParameters> parameters() {
        return payroll.listParameters();
    }

    @PutMapping("/parameters")
    public PayrollParameters saveParameters(@Valid @RequestBody PayrollService.ParamsRequest r) {
        return payroll.saveParameters(r);
    }

    @GetMapping("/employees")
    public List<Employee> employees() {
        return payroll.listEmployees();
    }

    @PostMapping("/employees")
    @ResponseStatus(HttpStatus.CREATED)
    public Employee createEmployee(@Valid @RequestBody PayrollService.EmployeeRequest r) {
        return payroll.saveEmployee(null, r);
    }

    @PutMapping("/employees/{id}")
    public Employee updateEmployee(@PathVariable Long id, @Valid @RequestBody PayrollService.EmployeeRequest r) {
        return payroll.saveEmployee(id, r);
    }

    @GetMapping("/periods")
    public List<PayrollService.PeriodSummary> periods() {
        return payroll.listPeriods();
    }

    @PostMapping("/periods")
    public PayrollService.PeriodDetail createPeriod(@Valid @RequestBody PayrollService.PeriodRequest r) {
        return payroll.createPeriod(r);
    }

    @GetMapping("/periods/{id}")
    public PayrollService.PeriodDetail period(@PathVariable Long id) {
        return payroll.detail(id);
    }

    @PostMapping("/periods/{id}/novelties")
    public PayrollService.PeriodDetail addNovelty(@PathVariable Long id, @Valid @RequestBody PayrollService.NoveltyRequest r) {
        return payroll.addNovelty(id, r);
    }

    @DeleteMapping("/periods/{id}/novelties/{noveltyId}")
    public PayrollService.PeriodDetail deleteNovelty(@PathVariable Long id, @PathVariable Long noveltyId) {
        return payroll.deleteNovelty(id, noveltyId);
    }

    @PostMapping("/periods/{id}/recalculate")
    public PayrollService.PeriodDetail recalculate(@PathVariable Long id) {
        return payroll.recalculate(id);
    }

    @PostMapping("/periods/{id}/approve")
    public PayrollService.PeriodDetail approve(@PathVariable Long id, @RequestBody(required = false) PayrollService.ApproveRequest r) {
        return payroll.approve(id, r);
    }

    @PostMapping("/periods/{id}/transmit")
    public PayrollService.PeriodDetail transmit(@PathVariable Long id) {
        return payroll.transmit(id);
    }

    public record DeleteRequest(String reason) {
    }

    @PostMapping("/payslips/{id}/delete-electronic")
    public PayrollService.PeriodDetail deleteElectronic(@PathVariable Long id, @RequestBody(required = false) DeleteRequest r) {
        return payroll.deleteBeforeDian(id, r == null ? null : r.reason());
    }
}
