package com.tiendaposs.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Error de negocio con mensaje en español pensado para mostrarse tal cual al usuario. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.details = details;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    /** Un campo concreto del formulario es inválido: el frontend lo muestra junto al campo. */
    public static ApiException field(String field, String reason) {
        return new ApiException(HttpStatus.BAD_REQUEST, "Revisa los datos", Map.of(field, reason));
    }
}
