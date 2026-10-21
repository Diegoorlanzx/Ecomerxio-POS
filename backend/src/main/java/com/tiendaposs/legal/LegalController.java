package com.tiendaposs.legal;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/legal")
public class LegalController {

    private final LegalService legal;

    public LegalController(LegalService legal) {
        this.legal = legal;
    }

    @GetMapping("/status")
    public LegalService.Status status() {
        return legal.status();
    }

    @PostMapping("/accept")
    public LegalService.Status accept(@RequestBody LegalService.AcceptRequest r) {
        return legal.accept(r);
    }
}
