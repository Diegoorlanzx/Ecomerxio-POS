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
                             BigDecimal stockValue, Instant createdAt, Instant updatedAt) {
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
            String priceChangeReason) {
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
                             BigDecimal taxRate, BigDecimal fixedTax, boolean favorite, List<PosPresentation> presentations) {
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
