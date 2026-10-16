package com.tiendaposs.catalog;

import com.tiendaposs.catalog.CatalogDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalog;
    private final ProductCsvService csv;
    private final ProductBulkService bulk;
    private final AdvancedCatalogService advanced;

    public CatalogController(CatalogService catalog, ProductCsvService csv, ProductBulkService bulk, AdvancedCatalogService advanced) {
        this.catalog = catalog;
        this.advanced = advanced;
        this.csv = csv;
        this.bulk = bulk;
    }

    @GetMapping("/categories")
    @PreAuthorize("hasAnyAuthority('PRODUCTOS_VER','POS_VENDER','INVENTARIO_VER')")
    public List<CategoryDto> categories() {
        return catalog.listCategories();
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PRODUCTOS_EDITAR')")
    public CategoryDto createCategory(@Valid @RequestBody CategoryRequest r) {
        return catalog.saveCategory(null, r);
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('PRODUCTOS_EDITAR')")
    public CategoryDto updateCategory(@PathVariable Long id, @Valid @RequestBody CategoryRequest r) {
        return catalog.saveCategory(id, r);
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PRODUCTOS_EDITAR')")
    public void deleteCategory(@PathVariable Long id) {
        catalog.deleteCategory(id);
    }

    @GetMapping("/products")
    @PreAuthorize("hasAnyAuthority('PRODUCTOS_VER','INVENTARIO_VER')")
    public PageDto<ProductDto> products(@RequestParam(required = false) String q,
                                        @RequestParam(required = false) Long categoryId,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String stock,
                                        @RequestParam(required = false) String sort,
                                        @RequestParam(required = false) String level,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        return catalog.search(q, categoryId, status, stock, level, Math.max(page, 0), Math.min(Math.max(size, 1), 200), sort);
    }

    @GetMapping("/products/{id}")
    @PreAuthorize("hasAnyAuthority('PRODUCTOS_VER','INVENTARIO_VER','POS_VENDER')")
    public ProductDto product(@PathVariable Long id) {
        return catalog.get(id);
    }

    @GetMapping("/products/barcode/{code}")
    @PreAuthorize("hasAnyAuthority('PRODUCTOS_VER','INVENTARIO_VER','POS_VENDER')")
    public ProductDto byBarcode(@PathVariable String code) {
        return catalog.byBarcode(code);
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PRODUCTOS_EDITAR')")
    public ProductDto create(@Valid @RequestBody ProductRequest r) {
        return catalog.create(r);
    }

    @PutMapping("/products/{id}")
    @PreAuthorize("hasAuthority('PRODUCTOS_EDITAR')")
    public ProductDto update(@PathVariable Long id, @Valid @RequestBody ProductRequest r) {
        return catalog.update(id, r);
    }

    @PostMapping("/products/{id}/status")
    @PreAuthorize("hasAuthority('PRODUCTOS_EDITAR')")
    public ProductDto status(@PathVariable Long id, @Valid @RequestBody StatusRequest r) {
        return catalog.setStatus(id, r.status());
    }

    @GetMapping("/products/{id}/price-history")
    @PreAuthorize("hasAnyAuthority('PRODUCTOS_VER','INVENTARIO_VER')")
    public List<PriceHistoryDto> priceHistory(@PathVariable Long id) {
        return catalog.priceHistory(id);
    }

    @PostMapping("/products/bulk")
    @PreAuthorize("hasAnyAuthority('PRODUCTOS_EDITAR','PRODUCTOS_PRECIOS')")
    public ProductBulkService.BulkResult bulk(@Valid @RequestBody BulkRequest r) {
        return bulk.apply(r);
    }

    @GetMapping("/products/export")
    @PreAuthorize("hasAuthority('PRODUCTOS_IMPORTAR')")
    public ResponseEntity<byte[]> export() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"productos-" + LocalDate.now() + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.export().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/products/import-template")
    @PreAuthorize("hasAuthority('PRODUCTOS_IMPORTAR')")
    public ResponseEntity<byte[]> template() {
        String body = "﻿" + String.join(";", ProductCsvService.HEADERS) + "\r\n"
                + ";Cerveza Águila 330ml lata;Cervezas;Nacionales;Bavaria;UNIDAD;7702004003508;3500;3200;2400;48;24;120;12;IVA;0;0;activo;Six pack:6:20000:|Caja x24:24:78000:\r\n";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"plantilla-productos.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    public record ImportRequest(String csv) {
    }

    @PostMapping("/products/import")
    @PreAuthorize("hasAuthority('PRODUCTOS_IMPORTAR')")
    public ImportResult importCsv(@RequestBody ImportRequest r) {
        if (r.csv() == null || r.csv().length() > 5_000_000) {
            throw com.tiendaposs.common.ApiException.badRequest("El archivo está vacío o es demasiado grande (máx. 5 MB)");
        }
        return csv.importCsv(r.csv());
    }

    @GetMapping("/pos/catalog")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','PRODUCTOS_VER')")
    public List<PosProduct> posCatalog() {
        return catalog.posCatalog();
    }

    @PutMapping("/products/{id}/variants")
    @PreAuthorize("hasAuthority('PRODUCTOS_EDITAR')")
    public ProductDto variants(@PathVariable Long id, @Valid @RequestBody VariantsRequest r) {
        return advanced.saveVariants(id, r);
    }

    @PutMapping("/products/{id}/components")
    @PreAuthorize("hasAuthority('PRODUCTOS_EDITAR')")
    public ProductDto components(@PathVariable Long id, @Valid @RequestBody ComponentsRequest r) {
        return advanced.saveComponents(id, r);
    }

    @DeleteMapping("/products/{id}/components")
    @PreAuthorize("hasAuthority('PRODUCTOS_EDITAR')")
    public ProductDto removeComponents(@PathVariable Long id) {
        return advanced.removeComponents(id);
    }

    @GetMapping("/products/scale/{code}")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','PRODUCTOS_VER')")
    public ScaleCodeDto scale(@PathVariable String code) {
        return advanced.parseScale(code);
    }
}
