package com.tiendaposs.settings;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings/pos")
public class SettingsController {

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping
    public StoreSettings get() {
        return service.current();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public StoreSettings update(@Valid @RequestBody SettingsService.SettingsRequest req) {
        return service.update(req);
    }

    @PutMapping("/finance")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public StoreSettings updateFinance(@Valid @RequestBody SettingsService.FinanceParams req) {
        return service.updateFinance(req);
    }

    @PutMapping("/inventory")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public StoreSettings updateInventory(@Valid @RequestBody SettingsService.InventoryParams req) {
        return service.updateInventory(req);
    }
}
