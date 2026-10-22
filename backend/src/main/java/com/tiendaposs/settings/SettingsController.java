package com.tiendaposs.settings;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings/pos")
public class SettingsController {

    private final SettingsService service;
    private final ModulesService modules;

    public SettingsController(SettingsService service, ModulesService modules) {
        this.service = service;
        this.modules = modules;
    }

    @GetMapping("/modules")
    public ModulesService.ModulesDto modules() {
        return modules.get();
    }

    @PutMapping("/modules")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public ModulesService.ModulesDto updateModules(@Valid @RequestBody ModulesService.ModulesRequest req) {
        return modules.update(req);
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

    @PutMapping("/analytics")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public StoreSettings updateAnalytics(@Valid @RequestBody SettingsService.AnalyticsParams req) {
        return service.updateAnalytics(req);
    }

    @PutMapping("/finance")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public StoreSettings updateFinance(@Valid @RequestBody SettingsService.FinanceParams req) {
        return service.updateFinance(req);
    }

    @PutMapping("/receipt")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public StoreSettings updateReceipt(@RequestBody SettingsService.ReceiptParams req) {
        return service.updateReceipt(req);
    }

    @PutMapping("/inventory")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public StoreSettings updateInventory(@Valid @RequestBody SettingsService.InventoryParams req) {
        return service.updateInventory(req);
    }
}
