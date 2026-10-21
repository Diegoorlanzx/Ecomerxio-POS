package com.tiendaposs.quotes;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.auth.RateLimiter;
import com.tiendaposs.pos.SaleDtos.SaleDto;
import com.tiendaposs.quotes.QuoteService.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class QuoteController {

    private final QuoteService quotes;
    private final RateLimiter limiter;

    public QuoteController(QuoteService quotes, RateLimiter limiter) {
        this.quotes = quotes;
        this.limiter = limiter;
    }

    @GetMapping("/quotes")
    public List<SummaryDto> list(@RequestParam(defaultValue = "COTIZACION") String kind, @RequestParam(required = false) String status,
                                 @RequestParam(required = false) String q) {
        return quotes.list(kind, status, q);
    }

    @GetMapping("/quotes/{id}")
    public QuoteDto get(@PathVariable Long id) {
        return quotes.get(id);
    }

    @PostMapping("/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    public QuoteDto create(@RequestParam(defaultValue = "COTIZACION") String kind, @Valid @RequestBody QuoteRequest r) {
        return quotes.create(kind, r);
    }

    @PutMapping("/quotes/{id}")
    public QuoteDto update(@PathVariable Long id, @Valid @RequestBody QuoteRequest r) {
        return quotes.update(id, r);
    }

    @PostMapping("/quotes/{id}/sent")
    public QuoteDto sent(@PathVariable Long id) {
        return quotes.markSent(id);
    }

    @PostMapping("/quotes/{id}/decision")
    public QuoteDto decide(@PathVariable Long id, @Valid @RequestBody DecisionRequest r) {
        return quotes.decide(id, r);
    }

    @PostMapping("/quotes/{id}/prefactura")
    @ResponseStatus(HttpStatus.CREATED)
    public QuoteDto toPrefactura(@PathVariable Long id) {
        return quotes.toPrefactura(id);
    }

    @PostMapping("/quotes/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public QuoteDto duplicate(@PathVariable Long id) {
        return quotes.duplicate(id);
    }

    @GetMapping("/quotes/{id}/validation")
    public PrefacturaValidator.Result validation(@PathVariable Long id) {
        return quotes.validate(id);
    }

    @PostMapping("/quotes/{id}/approve")
    public QuoteDto approve(@PathVariable Long id, @RequestBody ApproveRequest r) {
        return quotes.approve(id, r);
    }

    @PostMapping("/quotes/{id}/charge")
    public SaleDto charge(@PathVariable Long id, @Valid @RequestBody ChargeRequest r) {
        return quotes.charge(id, r);
    }

    @PostMapping("/quotes/{id}/cancel")
    public QuoteDto cancel(@PathVariable Long id, @Valid @RequestBody CancelRequest r) {
        return quotes.cancel(id, r);
    }

    // ---------------- Enlace que se le comparte al cliente (sin sesión) ----------------

    @GetMapping("/public/quotes/{token}")
    public PublicQuote publicView(@PathVariable String token) {
        limiter.check("quote-public", AuditService.clientIp());
        return quotes.publicView(token);
    }

    @PostMapping("/public/quotes/{token}/decision")
    public PublicQuote publicDecide(@PathVariable String token, @Valid @RequestBody PublicDecision r) {
        limiter.check("quote-public", AuditService.clientIp());
        return quotes.publicDecide(token, r);
    }

    @PostMapping("/public/quotes/{token}/check")
    public PublicQuote publicCheck(@PathVariable String token, @Valid @RequestBody PublicCheck r) {
        limiter.check("quote-public", AuditService.clientIp());
        return quotes.publicCheck(token, r);
    }
}
