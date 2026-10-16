package com.tiendaposs.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class CatalogDtos {
    private CatalogDtos() {
    }

    public record CategoryDto(Long id, Long parentId, String name, String icon, int sortOrder, boolean active, long productCount) {
    }

    public record CategoryRequest(
            @NotBlank @Size(max = 80, message = "máximo 80 caracteres") String name,
            Long parentId,
            @Size(max = 40) String icon,
            Integer sortOrder,
            Boolean active) {
    }

    public record PresentationDto(Long id, String name, BigDecimal factor, String barcode, BigDecimal price,
                                  BigDecimal minPrice, boolean isBase, boolean active, BigDecimal marginPct) {
    }

    public record ProductDto(Long id, String sku, String name, String description, String brand, String photo,
                             Long categoryId, String categoryName, String unit, boolean trackStock, BigDecimal stock,
                             String stockStatus, BigDecimal cost, BigDecimal lastCost, BigDecimal stockMin,
                             BigDecimal stockMax, BigDecimal safetyStock, String taxName, BigDecimal taxRate,
                             BigDecimal fixedTax, String status, boolean favorite, List<PresentationDto> presentations,
                             BigDecimal stockValue, boolean trackLots, Instant createdAt, Instant updatedAt,
                             String productType, Long parentId, String parentName, String variantLabel,
                             java.util.Map<String, String> variantValues, List<VariantOption> variantOptions,
                             boolean trackSerials, int warrantyMonths, String plu,
                             List<VariantDto> variants, List<ComponentDto> components) {
    }

    public record VariantOption(@NotBlank @Size(max = 30, message = "máximo 30 caracteres") String name,
                                @NotNull @Size(min = 1, max = 30, message = "entre 1 y 30 valores") List<@NotBlank @Size(max = 30) String> values) {
    }

    /** Una variante dentro del padre (resumen para la tabla de variantes). */
    public record VariantDto(Long id, String sku, String label, java.util.Map<String, String> values, Long presentationId,
                             String barcode, BigDecimal price, BigDecimal cost, BigDecimal stock, String stockStatus, String status) {
    }

    public record ComponentDto(Long productId, String sku, String name, String unit, BigDecimal quantity, BigDecimal unitCost,
                               BigDecimal stock, boolean trackStock) {
    }

    /** Matriz de variantes: crea las combinaciones nuevas, actualiza las que ya existen y descontinúa las que salen. */
    public record VariantsRequest(@NotNull @Size(min = 1, max = 3, message = "entre 1 y 3 opciones (p. ej. talla y color)") List<@Valid VariantOption> options,
                                  @NotNull @Size(min = 1, max = 300, message = "entre 1 y 300 variantes") List<@Valid VariantRow> variants) {
    }

    public record VariantRow(Long id,
                             @NotNull java.util.Map<String, String> values,
                             @Size(max = 40) String sku,
                             @Size(max = 60) String barcode,
                             @NotNull @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal price,
                             @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal cost,
                             @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal initialStock,
                             Boolean active) {
    }

    public record ComponentsRequest(@NotNull @Size(min = 1, max = 30, message = "entre 1 y 30 componentes") List<@Valid ComponentRequest> components) {
    }

    public record ComponentRequest(@NotNull Long productId,
                                   @NotNull @DecimalMin(value = "0.001", message = "debe ser mayor que cero") BigDecimal quantity) {
    }

    /** Lo que trae una etiqueta de balanza: el producto, su presentación base y la cantidad (o el valor) impreso. */
    public record ScaleCodeDto(String code, String plu, Long productId, String productName, Long presentationId,
                               BigDecimal quantity, BigDecimal price, BigDecimal total, String mode) {
    }

    public record PresentationRequest(
            Long id,
            @NotBlank @Size(max = 60, message = "máximo 60 caracteres") String name,
            @NotNull @DecimalMin(value = "0.001", message = "debe ser mayor que cero") BigDecimal factor,
            @Size(max = 60, message = "máximo 60 caracteres") String barcode,
            @NotNull @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal price,
            @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal minPrice,
            boolean isBase,
            Boolean active) {
    }

    public record ProductRequest(
            @Size(max = 40, message = "máximo 40 caracteres") String sku,
            @NotBlank @Size(max = 160, message = "máximo 160 caracteres") String name,
            @Size(max = 1000, message = "máximo 1000 caracteres") String description,
            @Size(max = 80, message = "máximo 80 caracteres") String brand,
            String photo,
            Long categoryId,
            @NotBlank @Pattern(regexp = "UNIDAD|KILOGRAMO|GRAMO|LITRO|MILILITRO|METRO|LIBRA", message = "unidad no válida") String unit,
            Boolean trackStock,
            @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal cost,
            @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal initialStock,
            @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal stockMin,
            @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal stockMax,
            @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal safetyStock,
            @Size(max = 30, message = "máximo 30 caracteres") String taxName,
            @DecimalMin(value = "0", message = "no puede ser negativo") @DecimalMax(value = "100", message = "máximo 100") BigDecimal taxRate,
            @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal fixedTax,
            Boolean favorite,
            @NotNull @Size(min = 1, message = "agrega al menos una presentación") List<@Valid PresentationRequest> presentations,
            String priceChangeReason,
            Boolean trackLots,
            Boolean trackSerials,
            @Min(value = 0, message = "no puede ser negativo") @Max(value = 120, message = "máximo 120 meses") Integer warrantyMonths,
            @Pattern(regexp = "\\d{0,6}", message = "solo números, hasta 6 dígitos") String plu) {
    }

    public record StatusRequest(@NotBlank @Pattern(regexp = "ACTIVE|DISCONTINUED", message = "estado no válido") String status) {
    }

    public record PriceHistoryDto(Long id, Long presentationId, String presentationName, String field, BigDecimal oldValue,
                                  BigDecimal newValue, String reason, String userName, Instant createdAt) {
    }

    public record PageDto<T>(List<T> items, long total, int page, int pages) {
    }

    /** Producto compacto para la pantalla de caja (y su copia sin internet). */
    public record PosProduct(Long id, String sku, String name, String brand, String photo, Long categoryId,
                             String unit, boolean trackStock, BigDecimal stock, String stockStatus, String taxName,
                             BigDecimal taxRate, BigDecimal fixedTax, boolean favorite, List<PosPresentation> presentations,
                             String productType, Long parentId, String variantLabel, java.util.Map<String, String> variantValues,
                             List<VariantOption> variantOptions, boolean trackSerials, String plu) {
    }

    public record PosPresentation(Long id, String name, BigDecimal factor, String barcode, BigDecimal price,
                                  BigDecimal minPrice, boolean isBase) {
    }

    public record BulkRequest(
            @NotNull @Size(min = 1, message = "elige al menos un producto") List<Long> productIds,
            @NotBlank @Pattern(regexp = "PRECIO_PORCENTAJE|PRECIO_FIJO|STOCK_MINIMO|STOCK_MAXIMO|CATEGORIA|ESTADO|IMPUESTO", message = "acción no válida") String action,
            BigDecimal value,
            Integer roundTo,
            Long categoryId,
            String status,
            String reason) {
    }

    public record ImportResult(int created, int updated, List<String> errors) {
    }
}
