package com.tiendaposs.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ApiError(int status, String message, Map<String, Object> fields, Instant timestamp) {
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String message, Map<String, Object> fields) {
        return ResponseEntity.status(status).body(new ApiError(status.value(), message, fields, Instant.now()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        return error(ex.getStatus(), ex.getMessage(), ex.getDetails());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            String code = fe.getCode() == null ? "" : fe.getCode();
            boolean empty = code.equals("NotBlank") || code.equals("NotNull") || code.equals("NotEmpty");
            String msg = fe.getDefaultMessage();
            // Mensajes propios del DTO (en español) se respetan; los genéricos vacíos pasan a "es obligatorio".
            boolean custom = msg != null && !msg.startsWith("no debe") && !msg.startsWith("must");
            fields.putIfAbsent(fe.getField(), empty && !custom ? "es obligatorio" : msg);
        }
        return error(HttpStatus.BAD_REQUEST, "Revisa los datos", fields);
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ApiError> handleDenied(Exception ex) {
        return error(HttpStatus.FORBIDDEN, "No tienes permiso para esta acción", null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> handleBadInput(Exception ex) {
        return error(HttpStatus.BAD_REQUEST, "La solicitud no es válida", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "Método no permitido", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoResourceFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "No encontrado", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Error inesperado", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Ocurrió un error inesperado. Intenta de nuevo.", null);
    }
}
