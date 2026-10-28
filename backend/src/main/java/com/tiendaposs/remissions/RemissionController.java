package com.tiendaposs.remissions;

import com.tiendaposs.pos.SaleDtos.SaleDto;
import com.tiendaposs.remissions.RemissionService.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class RemissionController {

    private final RemissionService remissions;

    public RemissionController(RemissionService remissions) {
        this.remissions = remissions;
    }

    @GetMapping("/remissions")
    public List<SummaryDto> list(@RequestParam(required = false) String status, @RequestParam(required = false) Long customerId,
                                 @RequestParam(required = false) String q) {
        return remissions.list(status, customerId, q);
    }

    @GetMapping("/remissions/{id}")
    public RemissionDto get(@PathVariable Long id) {
        return remissions.get(id);
    }

    @PostMapping("/remissions")
    @ResponseStatus(HttpStatus.CREATED)
    public RemissionDto create(@Valid @RequestBody RemissionRequest r) {
        return remissions.create(r);
    }

    @PutMapping("/remissions/{id}")
    public RemissionDto update(@PathVariable Long id, @Valid @RequestBody RemissionRequest r) {
        return remissions.update(id, r);
    }

    @PostMapping("/quotes/{quoteId}/remission")
    @ResponseStatus(HttpStatus.CREATED)
    public RemissionDto fromQuote(@PathVariable Long quoteId) {
        return remissions.fromQuote(quoteId);
    }

    @PostMapping("/remissions/{id}/deliver")
    public RemissionDto deliver(@PathVariable Long id, @Valid @RequestBody DeliverRequest r) {
        return remissions.deliver(id, r);
    }

    @PostMapping("/remissions/{id}/returns")
    public RemissionDto returnItems(@PathVariable Long id, @Valid @RequestBody ReturnRequest r) {
        return remissions.returnItems(id, r);
    }

    @PostMapping("/remissions/{id}/cancel")
    public RemissionDto cancel(@PathVariable Long id, @Valid @RequestBody CancelRequest r) {
        return remissions.cancel(id, r);
    }

    @PostMapping("/remissions/invoice")
    public SaleDto invoice(@Valid @RequestBody InvoiceRequest r) {
        return remissions.invoice(r);
    }
}
